package com.attentiontop.basselai

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var dbHelper: AppDatabase
    private lateinit var inputRequest: EditText
    private lateinit var btnAnalyze: Button
    private lateinit var approvalLayout: LinearLayout
    private lateinit var btnApprove: Button
    private lateinit var btnReject: Button
    private lateinit var txtResult: TextView
    private lateinit var txtStatus: TextView
    private lateinit var btnModeSelector: Button

    private var currentProjectId: Long = -1L
    private var isNetworkCallInProgress: Boolean = false
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // خيارات التحكم اليدوي أو التلقائي
    private var selectedTargetAgent: String = "AUTO" // AUTO, PLANNER, EXECUTOR
    private var selectedTargetProvider: String = "AUTO" // AUTO, GEMINI, GROQ

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupCommandCenterUI()

        try {
            dbHelper = AppDatabase(this)
            restoreLatestProject()
        } catch (e: Exception) {
            txtStatus.text = "خطأ في التهيئة: ${e.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
    }

    private fun getPrefs() = getSharedPreferences("bassel_prefs", Context.MODE_PRIVATE)

    private fun getActiveGeminiKey(): String {
        val saved = getPrefs().getString("gemini_key", "")?.trim() ?: ""
        if (saved.isNotEmpty()) return saved
        val buildKey = try { BuildConfig.GEMINI_API_KEY.trim() } catch (_: Exception) { "" }
        return if (buildKey != "YOUR_GEMINI_API_KEY" && buildKey != "null") buildKey else ""
    }

    private fun getActiveGroqKey(): String {
        val saved = getPrefs().getString("groq_key", "")?.trim() ?: ""
        if (saved.isNotEmpty()) return saved
        val buildKey = try { BuildConfig.GROQ_API_KEY.trim() } catch (_: Exception) { "" }
        return if (buildKey != "YOUR_GROQ_API_KEY" && buildKey != "null") buildKey else ""
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun setupCommandCenterUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(Color.parseColor("#0F121C"))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val headerTitle = TextView(this).apply {
            text = "Bassel AI - مجلس الإدارة والوكلاء الذكي"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#EEF2FA"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        }

        // شريط الأزرار العلوية
        val topBarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val btnArchive = createSmallButton("📂 الأرشيف", "#1B202E").apply {
            setOnClickListener { showProjectsArchiveDialog() }
        }
        val btnLogs = createSmallButton("📊 السجل", "#1B202E").apply {
            setOnClickListener { showAttemptsLogDialog() }
        }
        val btnSettings = createSmallButton("⚙️ المفاتيح", "#273248").apply {
            setOnClickListener { showApiKeysDialog() }
        }

        topBarLayout.addView(btnArchive)
        topBarLayout.addView(btnLogs)
        topBarLayout.addView(btnSettings)

        // زر اختيار نمط التشغيل (تلقائي أو مخصص)
        btnModeSelector = Button(this).apply {
            text = "🎯 النمط: المجلس التلقائي (الكل)"
            textSize = 12f
            setBackgroundColor(Color.parseColor("#273248"))
            setTextColor(Color.parseColor("#5B8DEF"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            setOnClickListener { showModeSelectionDialog() }
        }

        txtStatus = TextView(this).apply {
            text = "الحالة: جاهز لاستقبال الأوامر"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#5B8DEF"))
            setPadding(dp(4), 0, dp(4), dp(6))
        }

        inputRequest = EditText(this).apply {
            hint = "اكتب فكرتك أو طلبك البرمجي هنا..."
            setTextColor(Color.parseColor("#EEF2FA"))
            setHintTextColor(Color.parseColor("#A0ABBE"))
            setBackgroundColor(Color.parseColor("#1B202E"))
            minLines = 2
            maxLines = 4
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        btnAnalyze = Button(this).apply {
            text = "🚀 بدء التخطيط الهندسي (PLANNER)"
            setBackgroundColor(Color.parseColor("#5B8DEF"))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener {
                val prompt = inputRequest.text.toString().trim()
                if (prompt.isEmpty()) {
                    Toast.makeText(this@MainActivity, "الرجاء كتابة الطلب أولاً", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (isNetworkCallInProgress) {
                    Toast.makeText(this@MainActivity, "النظام يعمل حالياً، يرجى الانتظار...", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                startPlanningWorkflow(prompt)
            }
        }

        approvalLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        btnApprove = Button(this).apply {
            text = "✅ اعتماد وتنفيذ الكود"
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f).apply {
                marginEnd = dp(4)
            }
            setOnClickListener { startExecutionWorkflow() }
        }

        btnReject = Button(this).apply {
            text = "❌ رفض"
            setBackgroundColor(Color.parseColor("#C62828"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f).apply {
                marginStart = dp(4)
            }
            setOnClickListener { rejectCurrentPlan() }
        }

        approvalLayout.addView(btnApprove)
        approvalLayout.addView(btnReject)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dp(8) }
            setBackgroundColor(Color.parseColor("#121622"))
        }

        txtResult = TextView(this).apply {
            text = "أهلاً بك في نظام Bassel AI.\n\n1. اضغط على (⚙️ المفاتيح) لتأكيد مفاتيح Gemini و Groq.\n2. يمكنك تخصيص الوكيل والمحرك من زر (🎯 النمط).\n3. اكتب فكرتك وابدأ العمل."
            setTextColor(Color.parseColor("#EEF2FA"))
            textSize = 14f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        scrollView.addView(txtResult)

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val btnCopy = createSmallButton("📋 نسخ", "#1B202E").apply {
            setOnClickListener { copyResultToClipboard() }
        }
        val btnShare = createSmallButton("📤 مشاركة", "#1B202E").apply {
            setOnClickListener { shareResultText() }
        }
        val btnNew = createSmallButton("➕ مشروع جديد", "#311B92").apply {
            setOnClickListener { resetForNewProject() }
        }

        bottomBar.addView(btnCopy)
        bottomBar.addView(btnShare)
        bottomBar.addView(btnNew)

        rootLayout.addView(headerTitle)
        rootLayout.addView(topBarLayout)
        rootLayout.addView(btnModeSelector)
        rootLayout.addView(txtStatus)
        rootLayout.addView(inputRequest)
        rootLayout.addView(btnAnalyze)
        rootLayout.addView(approvalLayout)
        rootLayout.addView(scrollView)
        rootLayout.addView(bottomBar)

        setContentView(rootLayout)
    }

    private fun createSmallButton(label: String, hexColor: String): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            setBackgroundColor(Color.parseColor(hexColor))
            setTextColor(Color.parseColor("#EEF2FA"))
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
        }
    }

    private fun showModeSelectionDialog() {
        val options = arrayOf(
            "🔄 المجلس التلقائي المتكامل (Auto-Pilot)",
            "🧠 وكيل التخطيط فقط (Planner)",
            "⚙️ وكيل التنفيذ فقط (Executor)",
            "🤖 المحرك المباشر: Groq (gpt-oss-120b)",
            "🌐 المحرك المباشر: Gemini (1.5-Flash)"
        )
        AlertDialog.Builder(this)
            .setTitle("🎯 حدد نمط التشغيل والتحكم")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        selectedTargetAgent = "AUTO"
                        selectedTargetProvider = "AUTO"
                        btnModeSelector.text = "🎯 النمط: المجلس التلقائي (الكل)"
                    }
                    1 -> {
                        selectedTargetAgent = "PLANNER"
                        selectedTargetProvider = "AUTO"
                        btnModeSelector.text = "🎯 النمط المخصص: وكيل التخطيط (Planner)"
                    }
                    2 -> {
                        selectedTargetAgent = "EXECUTOR"
                        selectedTargetProvider = "AUTO"
                        btnModeSelector.text = "🎯 النمط المخصص: وكيل التنفيذ (Executor)"
                    }
                    3 -> {
                        selectedTargetAgent = "AUTO"
                        selectedTargetProvider = "GROQ"
                        btnModeSelector.text = "🎯 المحرك المباشر: Groq حصرياً"
                    }
                    4 -> {
                        selectedTargetAgent = "AUTO"
                        selectedTargetProvider = "GEMINI"
                        btnModeSelector.text = "🎯 المحرك المباشر: Gemini حصرياً"
                    }
                }
                Toast.makeText(this, "تم تغيير النمط بنجاح", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun displayProjectState(project: ProjectModel) {
        currentProjectId = project.id
        inputRequest.setText(project.request)

        when (project.status) {
            AppDatabase.STATUS_WAITING_FOR_APPROVAL -> {
                txtStatus.text = "الحالة: ⏳ بانتظار موافقتك"
                txtResult.text = "📋 [خطة العمل المقترحة للمشروع #${project.id}]:\n\n${project.plan}\n\n-------------------\nاضغط على (اعتماد وتنفيذ الكود) بالأسفل للبدء."
                approvalLayout.visibility = View.VISIBLE
            }
            AppDatabase.STATUS_COMPLETED -> {
                txtStatus.text = "الحالة: ✅ اكتمل التنفيذ"
                txtResult.text = "🚀 [المخرجات النهائية للمشروع #${project.id}]:\n\n${project.execution}\n\n===================\n📋 [الخطة]:\n${project.plan}"
                approvalLayout.visibility = View.GONE
            }
            else -> {
                txtStatus.text = "الحالة: مشروع #${project.id} (${project.status})"
                txtResult.text = project.plan.ifEmpty { "لا توجد مخرجات." }
                approvalLayout.visibility = View.GONE
            }
        }
    }

    private fun restoreLatestProject() {
        val project = dbHelper.getLatestProject()
        if (project != null) {
            displayProjectState(project)
        }
    }

    private fun resetForNewProject() {
        currentProjectId = -1L
        inputRequest.setText("")
        approvalLayout.visibility = View.GONE
        txtStatus.text = "الحالة: جاهز لمشروع جديد"
        txtResult.text = "أدخل فكرة مشروعك الجديد في الأعلى."
    }

    private fun startPlanningWorkflow(prompt: String) {
        if (getActiveGeminiKey().isEmpty() && getActiveGroqKey().isEmpty()) {
            txtStatus.text = "الحالة: ⚠️ المفاتيح غير موجودة"
            txtResult.text = "الرجاء النقر على (⚙️ المفاتيح) في الأعلى وإدخال مفتاح واحد على الأقل."
            showApiKeysDialog()
            return
        }

        isNetworkCallInProgress = true
        approvalLayout.visibility = View.GONE
        currentProjectId = dbHelper.createProject(prompt)

        txtStatus.text = "الحالة: 🧠 جاري معالجة الطلب..."
        txtResult.text = "الطلب: $prompt\n\n[جاري إرسال الطلب حسب النمط المختار...]"

        val role = if (selectedTargetAgent == "EXECUTOR") "executor" else "planner"
        val systemInstruction = if (role == "planner") {
            "أنت كبير مهندسي البرمجيات في Bassel AI. ضع خطة عمل هندسية واضحة للطلب التالي: $prompt"
        } else {
            "أنت المهندس المنفذ في Bassel AI. اكتب الأكواد البرمجية الكاملة للطلب: $prompt"
        }

        executeAiTaskSmart(role = role, promptText = systemInstruction) { success, output, provider, errorMsg ->
            if (success && output != null) {
                if (role == "planner") {
                    dbHelper.savePlanAndWaitApproval(currentProjectId, output)
                    txtStatus.text = "الحالة: ⏳ الخطة جاهزة (عبر $provider)"
                    txtResult.text = "📋 --- خطة العمل (عبر $provider) ---\n\n$output\n\n-------------------\nاضغط على اعتماد لتنفيذ الكود."
                    approvalLayout.visibility = View.VISIBLE
                } else {
                    dbHelper.saveExecutionAndComplete(currentProjectId, output)
                    txtStatus.text = "الحالة: ✅ اكتمل التنفيذ مباشرة (عبر $provider)"
                    txtResult.text = "🚀 --- المخرج النهائي (عبر $provider) ---\n\n$output"
                }
            } else {
                dbHelper.updateProjectStatus(currentProjectId, AppDatabase.STATUS_FAILED)
                txtStatus.text = "الحالة: ❌ فشل في التنفيذ"
                txtResult.text = "تعذر إتمام العملية.\nالسبب: $errorMsg"
            }
        }
    }

    private fun startExecutionWorkflow() {
        val project = dbHelper.getProjectById(currentProjectId) ?: return
        if (isNetworkCallInProgress) return

        isNetworkCallInProgress = true
        approvalLayout.visibility = View.GONE
        dbHelper.updateProjectStatus(currentProjectId, AppDatabase.STATUS_EXECUTING)

        txtStatus.text = "الحالة: ⚙️ وكيل التنفيذ يكتب الكود البرمجي..."
        txtResult.text = "[جاري تحويل الخطة إلى كود برمجي كامل...]"

        val executionPrompt = "أنت المهندس المنفذ في Bassel AI. اكتب الأكواد البرمجية الكاملة للطلب: ${project.request} بناءً على الخطة: ${project.plan}"

        executeAiTaskSmart(role = "executor", promptText = executionPrompt) { success, output, provider, errorMsg ->
            if (success && output != null) {
                dbHelper.saveExecutionAndComplete(currentProjectId, output)
                txtStatus.text = "الحالة: ✅ اكتمل التنفيذ (عبر $provider)"
                txtResult.text = "🚀 --- الكود النهائي (عبر $provider) ---\n\n$output"
            } else {
                dbHelper.updateProjectStatus(currentProjectId, AppDatabase.STATUS_WAITING_FOR_APPROVAL)
                approvalLayout.visibility = View.VISIBLE
                txtStatus.text = "الحالة: ⚠️ خطأ في التنفيذ"
                txtResult.text = "السبب: $errorMsg"
            }
        }
    }

    private fun rejectCurrentPlan() {
        if (currentProjectId != -1L) dbHelper.updateProjectStatus(currentProjectId, AppDatabase.STATUS_REJECTED)
        approvalLayout.visibility = View.GONE
        txtStatus.text = "الحالة: ❌ تم رفض الخطة"
        Toast.makeText(this, "تم رفض الخطة", Toast.LENGTH_SHORT).show()
    }

    // --- آلية التوجيه الذكي (حسب اختيار المستخدم أو التلقائي مع الـ Fallback) ---
    private fun executeAiTaskSmart(
        role: String,
        promptText: String,
        onComplete: (Boolean, String?, String, String) -> Unit
    ) {
        val geminiKey = getActiveGeminiKey()
        val groqKey = getActiveGroqKey()

        mainScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            var output: String? = null
            var usedProvider = ""
            var errorLog = ""

            // إذا حدد المستخدم مزوداً معيناً حصرياً
            if (selectedTargetProvider == "GROQ" && groqKey.isNotEmpty()) {
                try {
                    usedProvider = "Groq (محدد)"
                    output = callGroqApi(promptText, groqKey)
                } catch (e: Exception) {
                    errorLog += "Groq: ${e.message}; "
                }
            } else if (selectedTargetProvider == "GEMINI" && geminiKey.isNotEmpty()) {
                try {
                    usedProvider = "Gemini (محدد)"
                    output = callGeminiApi(promptText, geminiKey)
                } catch (e: Exception) {
                    errorLog += "Gemini: ${e.message}; "
                }
            } else {
                // النظام التلقائي (Fallback chain: Groq أولاً بنموذج openai/gpt-oss-120b ثم Gemini)
                if (output == null && groqKey.isNotEmpty()) {
                    try {
                        usedProvider = "Groq"
                        output = callGroqApi(promptText, groqKey)
                    } catch (e: Exception) {
                        errorLog += "Groq: ${e.message}; "
                    }
                }

                if (output == null && geminiKey.isNotEmpty()) {
                    try {
                        usedProvider = "Gemini"
                        output = callGeminiApi(promptText, geminiKey)
                    } catch (e: Exception) {
                        errorLog += "Gemini: ${e.message}; "
                    }
                }
            }

            val duration = System.currentTimeMillis() - startTime
            try {
                dbHelper.logAttempt(currentProjectId, usedProvider.ifEmpty { "None" }, role, output ?: "", if (output != null) "" else errorLog, duration)
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                isNetworkCallInProgress = false
                onComplete(output != null, output, usedProvider, errorLog.ifEmpty { "المفاتيح غير متوفرة" })
            }
        }
    }

    private fun callGroqApi(prompt: String, apiKey: String): String {
        // الاعتماد على النموذج الصحيح openai/gpt-oss-120b والمجرب لديك تماماً
        val urlString = "https://api.groq.com/openai/v1/chat/completions"
        val body = JSONObject().apply {
            put("model", "openai/gpt-oss-120b")
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", prompt))
            })
            put("temperature", 0.2)
        }

        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: throw IOException("استجابة فارغة")

            if (status !in 200..299) {
                throw IOException("HTTP $status: $response")
            }

            val json = JSONObject(response)
            return json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")?.trim()
                ?: throw EmptyStackException()
        } finally {
            connection.disconnect()
        }
    }

    private fun callGeminiApi(prompt: String, apiKey: String): String {
        val urlString = "https://generativelanguage.googleapis.com/v1/models/gemini-1.5-flash:generateContent?key=$apiKey"
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply { put(JSONObject().put("text", prompt)) })
                })
            })
        }

        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }

        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: throw IOException("استجابة فارغة")

            if (status !in 200..299) {
                throw IOException("HTTP $status: $response")
            }

            val json = JSONObject(response)
            return json.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                ?.optString("text", "") ?: throw IOException("استجابة فارغة من Gemini")
        } finally {
            connection.disconnect()
        }
    }

    private fun showApiKeysDialog() {
        val prefs = getPrefs()
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val geminiInput = EditText(this).apply { 
            hint = "مفتاح Gemini API Key"
            setText(prefs.getString("gemini_key", ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val groqInput = EditText(this).apply { 
            hint = "مفتاح Groq API Key"
            setText(prefs.getString("groq_key", ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val infoText = TextView(this).apply {
            text = "أدخل مفتاح Gemini و Groq الصحيح أدناه:"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(10))
        }

        layout.addView(infoText)
        layout.addView(geminiInput)
        layout.addView(groqInput)
        scrollView.addView(layout)

        AlertDialog.Builder(this)
            .setTitle("⚙️ إعدادات المفاتيح")
            .setView(scrollView)
            .setPositiveButton("حفظ وتفعيل") { _, _ ->
                prefs.edit()
                    .putString("gemini_key", geminiInput.text.toString().trim())
                    .putString("groq_key", groqInput.text.toString().trim())
                    .apply()
                Toast.makeText(this, "تم الحفظ بنجاح!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showProjectsArchiveDialog() {
        val projects = dbHelper.getAllProjects()
        if (projects.isEmpty()) {
            Toast.makeText(this, "الأرشيف فارغ", Toast.LENGTH_SHORT).show()
            return
        }
        val items = projects.map { p -> "#${p.id} [${p.status}] - ${if(p.request.length>30) p.request.take(30)+"..." else p.request}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("📂 الأرشيف")
            .setItems(items) { _, which -> displayProjectState(projects[which]) }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showAttemptsLogDialog() {
        if (currentProjectId == -1L) {
            Toast.makeText(this, "لا يوجد مشروع نشط", Toast.LENGTH_SHORT).show()
            return
        }
        val logs = dbHelper.getAttemptsForProject(currentProjectId)
        val msg = if (logs.isEmpty()) "لا توجد محاولات مسجلة." else logs.joinToString("\n\n-----------------\n\n")
        AlertDialog.Builder(this)
            .setTitle("📊 سجل أداء المحاولات")
            .setMessage(msg)
            .setPositiveButton("حسناً", null)
            .show()
    }

    private fun copyResultToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Bassel AI Output", txtResult.text.toString())
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "تم النسخ إلى الحافظة 📋", Toast.LENGTH_SHORT).show()
    }

    private fun shareResultText() {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, txtResult.text.toString())
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "مشاركة عبر:"))
    }
}
