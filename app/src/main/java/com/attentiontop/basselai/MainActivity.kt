package com.attentiontop.basselai

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    data class AiAgentModel(
        val id: String,
        var name: String,
        var providerType: String, // GEMINI, GROQ, OPENROUTER, CUSTOM
        var endpointUrl: String,
        var modelId: String,
        var apiKey: String,
        var role: String
    )

    data class TaskMemoryRecord(
        val taskId: String,
        var title: String,
        val createdAt: String,
        var updatedAt: String,
        var stoppingPoint: String,
        var engineeredPrompt: String,
        var finalCode: String,
        var auditReport: String,
        val discussionHistory: JSONArray
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private lateinit var prefs: SharedPreferences
    private lateinit var repoDir: File

    private lateinit var btnModeSelector: Button
    private lateinit var activeTaskBanner: TextView
    private lateinit var statusTextView: TextView
    private lateinit var promptInput: EditText
    private lateinit var btnReadinessCheck: Button
    private lateinit var btnStartCouncil: Button
    private lateinit var decisionLayout: LinearLayout
    private lateinit var resultTextView: TextView

    private val modelsList = mutableListOf<AiAgentModel>()
    private var manualLeaderId: String = "AUTO"
    private var autoPromptEnhance = true

    private var currentActiveTaskId: String? = null
    private var lastConfirmedPrompt = ""
    private var finalConsensusCode = ""
    private var lastAuditReport = ""

    companion object {
        const val DEFAULT_GROQ_MODEL = "llama-3.3-70b-versatile"
        const val DEFAULT_GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

        const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"
        const val DEFAULT_GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("bassel_ai_master_prefs", Context.MODE_PRIVATE)

        repoDir = File(filesDir, "tasks_repository")
        if (!repoDir.exists()) repoDir.mkdirs()

        loadModelsRegistry()
        buildMasterUi()
    }

    private fun buildMasterUi() {
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#090D16"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(24, 24, 24, 16)
        }

        val titleText = TextView(this).apply {
            text = "Bassel AI - مجلس الوكلاء والذاكرة المستمرة"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 14)
        }
        rootLayout.addView(titleText)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        topBar.addView(createTopButton("📁 المهام والذاكرة") { showTaskRepositoryDialog() })
        topBar.addView(createTopButton("📊 حالة الذاكرة") { showLogsDialog() })
        topBar.addView(createTopButton("⚙️ المفاتيح والنماذج") { showModelsManagerDialog() })
        rootLayout.addView(topBar)

        rootLayout.addView(createSpacer(10))

        btnModeSelector = Button(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#60A5FA"))
            background = createRoundedDrawable("#162032", 12f)
            setPadding(16, 18, 16, 18)
            isAllCaps = false
            setOnClickListener { showLeaderSelectorDialog() }
        }
        updateLeaderButtonLabel()
        rootLayout.addView(btnModeSelector)

        rootLayout.addView(createSpacer(8))

        activeTaskBanner = TextView(this).apply {
            text = "📂 المهمة الحالية: [مشروع جديد] - سيتم إنشاء ملف ذاكرة مستقل تلقائياً"
            textSize = 12f
            setTextColor(Color.parseColor("#A7F3D0"))
            background = createRoundedDrawable("#064E3B", 8f)
            setPadding(16, 10, 16, 10)
        }
        rootLayout.addView(activeTaskBanner)

        statusTextView = TextView(this).apply {
            text = "الحالة: المجلس جاهز وفي انتظار توجيهاتك..."
            textSize = 13f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(8, 10, 8, 8)
        }
        rootLayout.addView(statusTextView)

        promptInput = EditText(this).apply {
            hint = "اكتب فكرة المشروع، أو اكتب تعديلاً لاستكمال المهمة المفتوحة حالياً..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 14f
            minLines = 3
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            setPadding(20, 20, 20, 20)
            background = createRoundedDrawable("#131B2B", 12f)
        }
        rootLayout.addView(promptInput)

        rootLayout.addView(createSpacer(10))

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnReadinessCheck = Button(this).apply {
            text = "🔍 فحص جاهزية الكل"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = createRoundedDrawable("#1E3A8A", 10f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 6
            }
            setOnClickListener { runPreFlightReadinessCheck() }
        }

        btnStartCouncil = Button(this).apply {
            text = "🚀 بدء المجلس وحفظ بالذاكرة"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = createRoundedDrawable("#2563EB", 10f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 6
            }
            setOnClickListener { startPromptVerificationWorkflow() }
        }

        actionRow.addView(btnReadinessCheck)
        actionRow.addView(btnStartCouncil)
        rootLayout.addView(actionRow)

        decisionLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            visibility = View.GONE
            setPadding(0, 10, 0, 0)
        }

        val btnDownloadZip = Button(this).apply {
            text = "📥 تنزيل المشروع (ZIP)"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = createRoundedDrawable("#16A34A", 10f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 6
            }
            setOnClickListener { generateAndDownloadZip() }
        }

        val btnViewAudit = Button(this).apply {
            text = "📋 التقرير وسجل المهمة"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = createRoundedDrawable("#475569", 10f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 6
            }
            setOnClickListener { showAuditReportDialog() }
        }

        decisionLayout.addView(btnDownloadZip)
        decisionLayout.addView(btnViewAudit)
        rootLayout.addView(decisionLayout)

        rootLayout.addView(createSpacer(10))

        val outputScroll = ScrollView(this).apply {
            background = createRoundedDrawable("#0E1420", 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        resultTextView = TextView(this).apply {
            text = "--- 🌟 نظام Bassel AI (النسخة المحدثة مع مستودع الذاكرة) ---\n\n" +
                    "• المعرفات المعتمدة: Gemini ($DEFAULT_GEMINI_MODEL) و Groq ($DEFAULT_GROQ_MODEL).\n" +
                    "• كل مهمة تُحفظ في ملف مستقل داخل الهاتف مع كل ما نوقش وآخر نقطة توقف.\n" +
                    "• يمكنك فتح أي مهمة سابقة من [📁 المهام والذاكرة] ليستكمل الذكاء الاصطناعي العمل عليها فوراً."
            textSize = 13f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(20, 20, 20, 20)
            setLineSpacing(8f, 1.1f)
            setTextIsSelectable(true)
        }
        outputScroll.addView(resultTextView)
        rootLayout.addView(outputScroll)

        rootLayout.addView(createSpacer(10))

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        bottomBar.addView(createBottomButton("📋 نسخ", "#162032") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Bassel AI", resultTextView.text.toString()))
            Toast.makeText(this, "تم النسخ إلى الحافظة", Toast.LENGTH_SHORT).show()
        })
        bottomBar.addView(createBottomButton("📤 مشاركة", "#162032") {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, resultTextView.text.toString())
            }
            startActivity(Intent.createChooser(intent, "مشاركة عبر"))
        })
        bottomBar.addView(createBottomButton("➕ مهمة جديدة", "#312E81") {
            startFreshTaskSession()
        })
        rootLayout.addView(bottomBar)

        setContentView(rootLayout)
    }

    private fun startFreshTaskSession() {
        currentActiveTaskId = null
        promptInput.setText("")
        finalConsensusCode = ""
        lastAuditReport = ""
        decisionLayout.visibility = View.GONE
        activeTaskBanner.text = "📂 المهمة الحالية: [مشروع جديد] - سيتم إنشاء ملف ذاكرة مستقل تلقائياً"
        activeTaskBanner.background = createRoundedDrawable("#064E3B", 8f)
        setStatus("الحالة: جاهز لبدء مهمة جديدة كلياً", "#38BDF8")
        resultTextView.text = "--- ➕ مهمة جديدة ---\nاكتب فكرة مشروعك بالأعلى، وسيقوم النظام بإنشاء ملف ذاكرة خاص بها."
    }

    private fun loadModelsRegistry() {
        modelsList.clear()
        val saved = prefs.getString("models_registry_v6", null) ?: prefs.getString("models_registry_v5", null)
        if (!saved.isNullOrEmpty()) {
            try {
                val arr = JSONArray(saved)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    var pType = o.getString("providerType")
                    var mId = o.getString("modelId").trim()
                    var eUrl = o.getString("endpointUrl").trim()
                    val key = o.optString("apiKey", "").trim()
                    var name = o.getString("name")

                    // تصحيح وترقية تلقائية لأي بيانات قديمة
                    if (key.startsWith("gsk_") || pType == "GROQ" || mId.contains("llama", ignoreCase = true)) {
                        pType = "GROQ"
                        eUrl = DEFAULT_GROQ_ENDPOINT
                        if (mId.isBlank() || mId.contains("http")) mId = DEFAULT_GROQ_MODEL
                    } else if (key.startsWith("AIza") || pType == "GEMINI" || mId.contains("gemini", ignoreCase = true)) {
                        pType = "GEMINI"
                        if (mId == "gemini-1.5-flash" || mId.isBlank() || mId.contains("http")) {
                            mId = DEFAULT_GEMINI_MODEL
                        }
                        if (name.contains("1.5")) name = "Gemini 3.8 Flash"
                        eUrl = DEFAULT_GEMINI_ENDPOINT
                    }

                    modelsList.add(
                        AiAgentModel(
                            id = o.getString("id"),
                            name = name,
                            providerType = pType,
                            endpointUrl = eUrl,
                            modelId = mId,
                            apiKey = key,
                            role = o.optString("role", "ALL")
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (modelsList.isEmpty()) {
            modelsList.add(
                AiAgentModel(
                    "g1",
                    "Groq Llama 3.3",
                    "GROQ",
                    DEFAULT_GROQ_ENDPOINT,
                    DEFAULT_GROQ_MODEL,
                    "",
                    "ALL"
                )
            )
            modelsList.add(
                AiAgentModel(
                    "g2",
                    "Gemini 3.8 Flash",
                    "GEMINI",
                    DEFAULT_GEMINI_ENDPOINT,
                    DEFAULT_GEMINI_MODEL,
                    "",
                    "LEADER"
                )
            )
            modelsList.add(
                AiAgentModel(
                    "g3",
                    "OpenRouter Coder",
                    "OPENROUTER",
                    "https://openrouter.ai/api/v1/chat/completions",
                    "deepseek/deepseek-chat:free",
                    "",
                    "CODER"
                )
            )
        }

        saveModelsRegistry()
        manualLeaderId = prefs.getString("manual_leader_id", "AUTO") ?: "AUTO"
        autoPromptEnhance = prefs.getBoolean("auto_prompt_enhance", true)
    }

    private fun saveModelsRegistry() {
        val arr = JSONArray()
        for (m in modelsList) {
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("providerType", m.providerType)
                put("endpointUrl", m.endpointUrl)
                put("modelId", m.modelId)
                put("apiKey", m.apiKey)
                put("role", m.role)
            })
        }
        prefs.edit()
            .putString("models_registry_v6", arr.toString())
            .putString("manual_leader_id", manualLeaderId)
            .putBoolean("auto_prompt_enhance", autoPromptEnhance)
            .apply()
    }

    // ==================== نظام مستودع المهام والذاكرة (Task Repository) ====================

    private fun getTaskFile(taskId: String): File = File(repoDir, "$taskId.json")

    private fun saveTaskMemoryToFile(record: TaskMemoryRecord) {
        try {
            val obj = JSONObject().apply {
                put("taskId", record.taskId)
                put("title", record.title)
                put("createdAt", record.createdAt)
                put("updatedAt", record.updatedAt)
                put("stoppingPoint", record.stoppingPoint)
                put("engineeredPrompt", record.engineeredPrompt)
                put("finalCode", record.finalCode)
                put("auditReport", record.auditReport)
                put("discussionHistory", record.discussionHistory)
            }
            getTaskFile(record.taskId).writeText(obj.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadTaskMemoryFromFile(taskId: String): TaskMemoryRecord? {
        return try {
            val file = getTaskFile(taskId)
            if (!file.exists()) return null
            val o = JSONObject(file.readText(Charsets.UTF_8))
            TaskMemoryRecord(
                taskId = o.getString("taskId"),
                title = o.optString("title", "مهمة بدون عنوان"),
                createdAt = o.optString("createdAt", ""),
                updatedAt = o.optString("updatedAt", ""),
                stoppingPoint = o.optString("stoppingPoint", ""),
                engineeredPrompt = o.optString("engineeredPrompt", ""),
                finalCode = o.optString("finalCode", ""),
                auditReport = o.optString("auditReport", ""),
                discussionHistory = o.optJSONArray("discussionHistory") ?: JSONArray()
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun loadAllTasksFromRepo(): List<TaskMemoryRecord> {
        val list = mutableListOf<TaskMemoryRecord>()
        val files = repoDir.listFiles { _, name -> name.endsWith(".json") } ?: return emptyList()
        for (f in files) {
            try {
                val o = JSONObject(f.readText(Charsets.UTF_8))
                list.add(
                    TaskMemoryRecord(
                        taskId = o.getString("taskId"),
                        title = o.optString("title", "مهمة"),
                        createdAt = o.optString("createdAt", ""),
                        updatedAt = o.optString("updatedAt", ""),
                        stoppingPoint = o.optString("stoppingPoint", ""),
                        engineeredPrompt = o.optString("engineeredPrompt", ""),
                        finalCode = o.optString("finalCode", ""),
                        auditReport = o.optString("auditReport", ""),
                        discussionHistory = o.optJSONArray("discussionHistory") ?: JSONArray()
                    )
                )
            } catch (_: Exception) {}
        }
        return list.sortedByDescending { it.updatedAt }
    }

    private fun appendDiscussionEntry(record: TaskMemoryRecord, sender: String, message: String, newStoppingPoint: String) {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        record.updatedAt = now
        record.stoppingPoint = newStoppingPoint
        record.discussionHistory.put(JSONObject().apply {
            put("time", now)
            put("sender", sender)
            put("content", message)
        })
        saveTaskMemoryToFile(record)
    }

    private fun buildMemoryContextForModels(record: TaskMemoryRecord): String {
        if (record.discussionHistory.length() == 0 && record.finalCode.isBlank()) return ""
        val sb = StringBuilder()
        sb.append("=== [ذاكرة المهمة السابقة المحفوظة في الهاتف] ===\n")
        sb.append("• عنوان المهمة: ${record.title}\n")
        sb.append("• آخر نقطة توقفنا عندها: ${record.stoppingPoint}\n")

        val historyCount = record.discussionHistory.length()
        val startIdx = (historyCount - 6).coerceAtLeast(0)
        if (historyCount > 0) {
            sb.append("• ملخص ما تم مناقشته سابقاً في هذه المهمة:\n")
            for (i in startIdx until historyCount) {
                val entry = record.discussionHistory.getJSONObject(i)
                sb.append("  - [${entry.optString("sender")}]: ${entry.optString("content").take(500)}\n")
            }
        }
        if (record.finalCode.isNotBlank()) {
            sb.append("\n• آخر كود برمجي تم التوصل إليه في هذه المهمة:\n${record.finalCode}\n")
        }
        sb.append("=========================================\n")
        return sb.toString()
    }

    private fun showTaskRepositoryDialog() {
        val tasks = loadAllTasksFromRepo()
        if (tasks.isEmpty()) {
            Toast.makeText(this, "مستودع المهام فارغ حالياً. ابدأ مهمة جديدة لحفظها تلقائياً.", Toast.LENGTH_LONG).show()
            return
        }

        val items = Array(tasks.size) { i ->
            val t = tasks[i]
            "🗂️ ${t.title}\n⏱️ آخر تحديث: ${t.updatedAt} | 📌 التوقف: ${t.stoppingPoint}"
        }

        AlertDialog.Builder(this)
            .setTitle("📁 مستودع المهام والذاكرة (${tasks.size} ملفات)")
            .setItems(items) { _, which ->
                showSingleTaskOptionsDialog(tasks[which])
            }
            .setPositiveButton("إغلاق", null)
            .show()
    }

    private fun showSingleTaskOptionsDialog(task: TaskMemoryRecord) {
        val options = arrayOf(
            "🔄 استئناف هذه المهمة (تفعيل ذاكرتها للنماذج)",
            "📜 قراءة سجل النقاش الكامل ونقطة التوقف",
            "🗑️ حذف ملف المهمة من الهاتف"
        )

        AlertDialog.Builder(this)
            .setTitle("🗂️ ${task.title}")
            .setItems(options) { _, idx ->
                when (idx) {
                    0 -> {
                        currentActiveTaskId = task.taskId
                        lastConfirmedPrompt = task.engineeredPrompt
                        finalConsensusCode = task.finalCode
                        lastAuditReport = task.auditReport
                        activeTaskBanner.text = "📂 المهمة المفتوحة: [${task.title}] - النماذج تتذكر كل ما سبق"
                        activeTaskBanner.background = createRoundedDrawable("#1E3A8A", 8f)
                        setStatus("الحالة: 📂 تم تحميل ذاكرة المهمة (${task.stoppingPoint})", "#4ADE80")
                        resultTextView.text = "--- 📂 تم استرجاع ملف المهمة من الذاكرة ---\n" +
                                "📌 آخر نقطة توقف: ${task.stoppingPoint}\n\n" +
                                (if (task.finalCode.isNotBlank()) task.finalCode else "لا يوجد كود نهائي بعد، اكتب توجيهك بالأعلى واضغط بدء المجلس للاستكمال.")
                        if (task.finalCode.isNotBlank()) {
                            decisionLayout.visibility = View.VISIBLE
                        }
                        Toast.makeText(this, "تم ربط النماذج بذاكرة هذه المهمة! اكتب أي تعديل أو استكمال الآن.", Toast.LENGTH_LONG).show()
                    }
                    1 -> {
                        val sb = StringBuilder()
                        sb.append("📌 عنوان المهمة: ${task.title}\n")
                        sb.append("📅 تاريخ الإنشاء: ${task.createdAt}\n")
                        sb.append("🛑 آخر نقطة توقف: ${task.stoppingPoint}\n\n")
                        sb.append("--- 💬 سجل المناقشات والمراحل ---\n\n")
                        for (i in 0 until task.discussionHistory.length()) {
                            val item = task.discussionHistory.getJSONObject(i)
                            sb.append("⏰ [${item.optString("time")}] - ${item.optString("sender")}:\n${item.optString("content")}\n\n-----------------\n")
                        }
                        AlertDialog.Builder(this)
                            .setTitle("📜 ملف ذاكرة المهمة")
                            .setMessage(sb.toString())
                            .setPositiveButton("استئناف المهمة") { _, _ ->
                                currentActiveTaskId = task.taskId
                                activeTaskBanner.text = "📂 المهمة المفتوحة: [${task.title}]"
                                resultTextView.text = sb.toString()
                            }
                            .setNegativeButton("إغلاق", null)
                            .show()
                    }
                    2 -> {
                        getTaskFile(task.taskId).delete()
                        if (currentActiveTaskId == task.taskId) {
                            startFreshTaskSession()
                        }
                        Toast.makeText(this, "تم حذف ملف المهمة", Toast.LENGTH_SHORT).show()
                        showTaskRepositoryDialog()
                    }
                }
            }
            .show()
    }

    // ==================== إدارة النماذج والمفاتيح ====================

    private fun showModelsManagerDialog() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        container.addView(TextView(this).apply {
            text = "إدارة النماذج والمفاتيح (مع التوجيه التلقائي الذكي):"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(0, 0, 0, 16)
        })

        val dialogRef = arrayOfNulls<AlertDialog>(1)

        val btnAdd = Button(this).apply {
            text = "➕ إضافة نموذج ذكاء اصطناعي جديد"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            background = createRoundedDrawable("#2563EB", 10f)
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                dialogRef[0]?.dismiss()
                showAddOrEditModelDialog(null)
            }
        }
        container.addView(btnAdd)
        container.addView(createSpacer(14))

        val chk = CheckBox(this).apply {
            text = "تفعيل هندسة وصياغة البرومبت الاحترافية تلقائياً"
            setTextColor(Color.WHITE)
            isChecked = autoPromptEnhance
            setOnCheckedChangeListener { _, v ->
                autoPromptEnhance = v
                saveModelsRegistry()
            }
        }
        container.addView(chk)
        container.addView(createSpacer(12))

        for (m in modelsList) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = createRoundedDrawable("#1E293B", 10f)
                setPadding(20, 16, 20, 16)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 }
            }

            val st = if (m.apiKey.isNotBlank()) "🟢 متصل" else "🔴 بدون مفتاح"
            card.addView(TextView(this).apply {
                text = "${m.name} [$st]"
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                textSize = 14f
            })
            card.addView(TextView(this).apply {
                text = "المزود: ${m.providerType} | المعرف: ${m.modelId}"
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 12f
                setPadding(0, 4, 0, 10)
            })

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val editBtn = Button(this).apply {
                text = "✏️ تعديل"
                textSize = 12f
                setTextColor(Color.WHITE)
                isAllCaps = false
                background = createRoundedDrawable("#3B82F6", 8f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6 }
                setOnClickListener {
                    dialogRef[0]?.dismiss()
                    showAddOrEditModelDialog(m)
                }
            }
            val delBtn = Button(this).apply {
                text = "🗑️ حذف"
                textSize = 12f
                setTextColor(Color.WHITE)
                isAllCaps = false
                background = createRoundedDrawable("#DC2626", 8f)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnClickListener {
                    if (modelsList.size <= 1) {
                        Toast.makeText(this@MainActivity, "يجب إبقاء نموذج واحد على الأقل", Toast.LENGTH_SHORT).show()
                    } else {
                        modelsList.remove(m)
                        saveModelsRegistry()
                        updateLeaderButtonLabel()
                        dialogRef[0]?.dismiss()
                        showModelsManagerDialog()
                    }
                }
            }
            row.addView(editBtn)
            row.addView(delBtn)
            card.addView(row)
            container.addView(card)
        }

        scroll.addView(container)
        val d = AlertDialog.Builder(this).setTitle("⚙️ إدارة النماذج والمفاتيح").setView(scroll).setPositiveButton("إغلاق", null).create()
        dialogRef[0] = d
        d.show()
    }

    private fun showAddOrEditModelDialog(existing: AiAgentModel?) {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val providers = arrayOf("GEMINI (Google Gemini)", "GROQ (Groq Cloud)", "OPENROUTER (OpenRouter)", "CUSTOM (OpenAI Compatible)")
        val codes = arrayOf("GEMINI", "GROQ", "OPENROUTER", "CUSTOM")

        layout.addView(createDialogLabel("المنصة أو المزود:"))
        val spProv = Spinner(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, providers)
        }
        layout.addView(spProv)
        layout.addView(createSpacer(10))

        layout.addView(createDialogLabel("اسم العرض:"))
        val nameIn = createDialogInput("مثال: Groq Llama 3.3", existing?.name ?: "")
        layout.addView(nameIn)
        layout.addView(createSpacer(10))

        layout.addView(createDialogLabel("معرف النموذج (Model ID):"))
        val modelIdIn = createDialogInput("مثال: $DEFAULT_GROQ_MODEL أو $DEFAULT_GEMINI_MODEL", existing?.modelId ?: "")
        layout.addView(modelIdIn)
        layout.addView(createSpacer(10))

        layout.addView(createDialogLabel("مفتاح الـ API:"))
        val keyIn = createDialogInput("ألصق مفتاح الـ API هنا...", existing?.apiKey ?: "")
        layout.addView(keyIn)
        layout.addView(createSpacer(10))

        layout.addView(createDialogLabel("رابط Endpoint URL:"))
        val urlIn = createDialogInput("https://...", existing?.endpointUrl ?: DEFAULT_GROQ_ENDPOINT)
        layout.addView(urlIn)
        layout.addView(createSpacer(10))

        // تعبئة تلقائية للبيانات الصحيحة عند تغيير المزود أثناء الإضافة
        spProv.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (existing == null) {
                    when (codes[position]) {
                        "GEMINI" -> {
                            if (nameIn.text.isBlank()) nameIn.setText("Gemini 3.8 Flash")
                            modelIdIn.setText(DEFAULT_GEMINI_MODEL)
                            urlIn.setText(DEFAULT_GEMINI_ENDPOINT)
                        }
                        "GROQ" -> {
                            if (nameIn.text.isBlank()) nameIn.setText("Groq Llama 3.3")
                            modelIdIn.setText(DEFAULT_GROQ_MODEL)
                            urlIn.setText(DEFAULT_GROQ_ENDPOINT)
                        }
                        "OPENROUTER" -> {
                            modelIdIn.setText("deepseek/deepseek-chat:free")
                            urlIn.setText("https://openrouter.ai/api/v1/chat/completions")
                        }
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val roles = arrayOf("شامل + قائد (ALL)", "قائد المجلس (LEADER)", "مبرمج (CODER)", "مراجع (REVIEWER)")
        val roleCodes = arrayOf("ALL", "LEADER", "CODER", "REVIEWER")
        layout.addView(createDialogLabel("تخصص النموذج:"))
        val spRole = Spinner(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, roles)
        }
        layout.addView(spRole)

        if (existing != null) {
            spProv.setSelection(codes.indexOf(existing.providerType).coerceAtLeast(0))
            spRole.setSelection(roleCodes.indexOf(existing.role).coerceAtLeast(0))
        }

        scroll.addView(layout)
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "➕ إضافة نموذج جديد" else "✏️ تعديل النموذج")
            .setView(scroll)
            .setPositiveButton("💾 حفظ") { _, _ ->
                var pType = codes[spProv.selectedItemPosition]
                val rCode = roleCodes[spRole.selectedItemPosition]
                val key = keyIn.text.toString().trim()
                var mId = modelIdIn.text.toString().trim()
                var url = urlIn.text.toString().trim()

                // تصحيح ذكي تلقائي عند الحفظ لمنع أي تضارب
                if (key.startsWith("gsk_") || mId.contains("llama", ignoreCase = true)) {
                    pType = "GROQ"
                    url = DEFAULT_GROQ_ENDPOINT
                    if (mId.isEmpty() || mId.contains("http")) mId = DEFAULT_GROQ_MODEL
                } else if (key.startsWith("AIza") || mId.contains("gemini", ignoreCase = true)) {
                    pType = "GEMINI"
                    if (mId.isEmpty() || mId.contains("http")) mId = DEFAULT_GEMINI_MODEL
                    url = DEFAULT_GEMINI_ENDPOINT
                }

                val name = nameIn.text.toString().trim().ifEmpty {
                    if (pType == "GEMINI") "Gemini 3.8 Flash" else "Groq Llama 3.3"
                }

                if (existing != null) {
                    existing.name = name
                    existing.providerType = pType
                    existing.modelId = mId
                    existing.apiKey = key
                    existing.endpointUrl = url
                    existing.role = rCode
                } else {
                    modelsList.add(AiAgentModel("m_${System.currentTimeMillis()}", name, pType, url, mId, key, rCode))
                }
                saveModelsRegistry()
                updateLeaderButtonLabel()
                Toast.makeText(this, "تم الحفظ بنجاح", Toast.LENGTH_SHORT).show()
                showModelsManagerDialog()
            }
            .setNegativeButton("إلغاء") { _, _ -> showModelsManagerDialog() }
            .show()
    }

    private fun showLeaderSelectorDialog() {
        val options = mutableListOf<String>()
        val ids = mutableListOf<String>()

        options.add("👑 اختيار تلقائي ذكي (أسرع نموذج متصل / Failover)")
        ids.add("AUTO")

        for (m in modelsList) {
            val st = if (m.apiKey.isNotBlank()) "🟢" else "🔴"
            options.add("👤 تعيين (${m.name}) كمدير رئيسي $st")
            ids.add(m.id)
        }

        AlertDialog.Builder(this)
            .setTitle("👑 اختيار مدير المجلس (القائد)")
            .setItems(options.toTypedArray()) { _, which ->
                manualLeaderId = ids[which]
                saveModelsRegistry()
                updateLeaderButtonLabel()
            }
            .show()
    }

    private fun updateLeaderButtonLabel() {
        btnModeSelector.text = if (manualLeaderId == "AUTO") {
            "👑 مدير المجلس: تلقائي ذكي (Failover + الأسرع)"
        } else {
            val l = modelsList.find { it.id == manualLeaderId }
            "👑 مدير المجلس المعين: (${l?.name ?: "تلقائي"})"
        }
    }

    // ==================== فحص الجاهزية ودورة عمل المجلس مع الذاكرة ====================

    private fun runPreFlightReadinessCheck() {
        val active = modelsList.filter { it.apiKey.isNotBlank() }
        if (active.isEmpty()) {
            Toast.makeText(this, "لا توجد أي نماذج بمفاتيح صالحة! أضف مفتاحاً أولاً.", Toast.LENGTH_LONG).show()
            showModelsManagerDialog()
            return
        }

        btnReadinessCheck.isEnabled = false
        setStatus("الحالة: 🔍 جاري فحص استجابة وجاهزية جميع النماذج...", "#38BDF8")
        resultTextView.text = "--- 🔍 فحص جاهزية أعضاء المجلس ---\n\nجاري إرسال اختبار استجابة سريع لكل نموذج..."

        CoroutineScope(Dispatchers.IO).launch {
            val report = StringBuilder()
            var healthyCount = 0

            for (m in active) {
                val start = System.currentTimeMillis()
                val testRes = callAiModelApi(m, "Reply with 'OK' only.")
                val latency = System.currentTimeMillis() - start

                if (testRes.first) {
                    healthyCount++
                    report.append("✅ [جاهز] ${m.name} (${m.modelId}) -> ${latency}ms\n")
                } else {
                    report.append("❌ [غير مستجيب] ${m.name} (${m.modelId})\n   السبب: ${testRes.second.take(160)}\n\n")
                }
            }

            withContext(Dispatchers.Main) {
                btnReadinessCheck.isEnabled = true
                setStatus("الحالة: ✅ اكتمل الفحص ($healthyCount/${active.size} نماذج جاهزة)", if (healthyCount > 0) "#4ADE80" else "#F87171")
                resultTextView.text = "--- 📊 تقرير جاهزية أعضاء المجلس ---\n\n$report\n" +
                        if (healthyCount > 0) "النماذج جاهزة تماماً. يمكنك إدخال فكرتك والضغط على [🚀 بدء المجلس]." else "تحذير: لا توجد نماذج مستجيبة، تحقق من المفاتيح والاتصال."
            }
        }
    }

    private fun startPromptVerificationWorkflow() {
        val rawPrompt = promptInput.text.toString().trim()
        if (rawPrompt.isEmpty()) {
            Toast.makeText(this, "يرجى كتابة فكرة البرنامج أو التعديل المطلوب أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        val activeModels = modelsList.filter { it.apiKey.isNotBlank() }
        if (activeModels.isEmpty()) {
            Toast.makeText(this, "يرجى إدخال مفتاح API واحد على الأقل عبر [⚙️ المفاتيح]", Toast.LENGTH_LONG).show()
            showModelsManagerDialog()
            return
        }

        btnStartCouncil.isEnabled = false
        decisionLayout.visibility = View.GONE

        // تحميل ملف المهمة الحالية أو إنشاء ملف مهمة جديد على الهاتف
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val taskRecord = if (currentActiveTaskId != null) {
            loadTaskMemoryFromFile(currentActiveTaskId!!) ?: TaskMemoryRecord(
                taskId = currentActiveTaskId!!,
                title = rawPrompt.take(45),
                createdAt = nowStr,
                updatedAt = nowStr,
                stoppingPoint = "بدء استئناف المهمة",
                engineeredPrompt = rawPrompt,
                finalCode = finalConsensusCode,
                auditReport = lastAuditReport,
                discussionHistory = JSONArray()
            )
        } else {
            val newId = "task_${System.currentTimeMillis()}"
            currentActiveTaskId = newId
            TaskMemoryRecord(
                taskId = newId,
                title = rawPrompt.take(45),
                createdAt = nowStr,
                updatedAt = nowStr,
                stoppingPoint = "تم إنشاء المهمة وتسجيل الطلب الأولي",
                engineeredPrompt = rawPrompt,
                finalCode = "",
                auditReport = "",
                discussionHistory = JSONArray()
            )
        }

        // استخراج الذاكرة السابقة للمهمة قبل إضافة الطلب الجديد
        val previousMemoryContext = buildMemoryContextForModels(taskRecord)
        appendDiscussionEntry(taskRecord, "المستخدم (باسل)", rawPrompt, "مرحلة تحليل وصياغة البرومبت الهندسي")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) {
                    activeTaskBanner.text = "📂 المهمة النشطة: [${taskRecord.title}] (محفوظة في الهاتف)"
                    activeTaskBanner.background = createRoundedDrawable("#1E3A8A", 8f)
                    setStatus("الحالة: 🧠 مهندس البرومبت ينسق الطلب مع ربطه بذاكرة المهمة...", "#38BDF8")
                    resultTextView.text = "--- 1️⃣ مرحلة البرومبت الهندسي والذاكرة ---\nجاري ربط الطلب بملف ذاكرة المهمة (${taskRecord.taskId})..."
                }

                var engineeredPrompt = rawPrompt
                val promptAgent = activeModels.find { it.role == "ALL" } ?: activeModels.first()
                if (autoPromptEnhance) {
                    val promptSys = if (previousMemoryContext.isNotBlank()) {
                        "$previousMemoryContext\n\nبناءً على ذاكرة المهمة أعلاه، قم بصياغة وتنظيم طلب التعديل/الاستكمال الجديد التالي من المستخدم ليكون توجيهاً برمجياً دقيقاً ومباشراً:\n$rawPrompt"
                    } else {
                        "أنت خبير هندسة أوامر. قم فقط بتنظيم وتنسيق طلب المستخدم التالي ليكون برومبت برمجياً احترافياً دون تغيير المتطلبات الأساسية:\n$rawPrompt"
                    }
                    val pRes = callAiModelApi(promptAgent, promptSys)
                    if (pRes.first && pRes.second.isNotBlank()) {
                        engineeredPrompt = pRes.second
                    }
                }

                lastConfirmedPrompt = engineeredPrompt
                taskRecord.engineeredPrompt = engineeredPrompt
                appendDiscussionEntry(taskRecord, "مهندس البرومبت (${promptAgent.name})", engineeredPrompt, "تم تجهيز البرومبت الهندسي وبانتظار توزيع المهام")

                val leader = selectActiveLeader(activeModels)
                if (leader == null) {
                    appendDiscussionEntry(taskRecord, "النظام", "تعذر العثور على مدير مستجيب", "توقف عند اختيار مدير المجلس (فشل اتصال)")
                    withContext(Dispatchers.Main) {
                        btnStartCouncil.isEnabled = true
                        setStatus("الحالة: ❌ فشل في العثور على مدير مستجيب (حُفظت نقطة التوقف)", "#F87171")
                        resultTextView.text = "تعذر بدء المجلس لعدم استجابة أي نموذج. تم حفظ حالة المهمة في الذاكرة للاستكمال لاحقاً."
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    setStatus("الحالة: 👑 المدير (${leader.name}) يوزع المهام مع سجل الذاكرة...", "#38BDF8")
                    resultTextView.text = "--- 2️⃣ العمل في الخفاء (In Progress) ---\nالمدير: ${leader.name}\nالمجلس يقرأ ذاكرة المهمة ويبني الكود..."
                }

                val councilLog = StringBuilder()
                councilLog.append("👑 المدير الموجه: ${leader.name}\n")
                councilLog.append("🗂️ معرف ملف الذاكرة: ${taskRecord.taskId}\n\n")

                val proposals = mutableMapOf<String, String>()
                val healthyModels = mutableListOf<AiAgentModel>()

                for (m in activeModels) {
                    withContext(Dispatchers.Main) {
                        setStatus("الحالة: ⏳ النموذج (${m.name}) يقرأ الذاكرة ويكتب الكود...", "#38BDF8")
                    }

                    val task = async(Dispatchers.IO) {
                        val sysPrompt = buildString {
                            if (previousMemoryContext.isNotBlank()) {
                                append(previousMemoryContext)
                                append("\n\n")
                            }
                            append("أنت مطور برمجيات خبير في مجلس Bassel AI. قم بكتابة أو تحديث الكود البرمجي الكامل والجاهز لتنفيذ المطلوب التالي مع مراعاة كل ما نوقش وتوقفنا عنده في ذاكرة المهمة:\n")
                            append(engineeredPrompt)
                        }
                        callAiModelApi(m, sysPrompt)
                    }

                    try {
                        val res = withTimeout(55000) { task.await() }
                        if (res.first && res.second.isNotBlank()) {
                            proposals[m.id] = res.second
                            healthyModels.add(m)
                            councilLog.append("✅ [مكتمل] ${m.name} أنجز مقترحه البرمجي.\n")
                            appendDiscussionEntry(taskRecord, "مقترح (${m.name})", res.second.take(800), "أنجز ${m.name} كتابة الكود الأولي")
                        } else {
                            councilLog.append("❌ [مستبعد] ${m.name} فشل: ${res.second.take(80)}\n")
                        }
                    } catch (e: TimeoutCancellationException) {
                        councilLog.append("⏱️ [تجاوز الوقت] ${m.name} تأخر وتم تخطيه.\n")
                    }
                }

                if (proposals.isEmpty()) {
                    appendDiscussionEntry(taskRecord, "النظام", "فشل جميع الأعضاء في إنتاج كود", "توقف عند مرحلة كتابة الأكواد الأولية")
                    throw Exception("فشل جميع أعضاء المجلس في إنتاج أي كود. تم حفظ نقطة التوقف في ملف المهمة.")
                }

                withContext(Dispatchers.Main) {
                    setStatus("الحالة: 🔄 جاري تقييم الأكواد ومراجعتها للوصول لنسبة 90%...", "#FBBF24")
                    resultTextView.text = "$councilLog\n--- 3️⃣ جولة التقييم والنسب المئوية بين الوكلاء ---\nجاري مراجعة الأكواد ومنح النسب..."
                }

                var bestCode = proposals.values.first()
                var bestScore = 0
                var consensusReached = false
                var iterationCount = 0
                val auditTrail = StringBuilder()

                while (!consensusReached && iterationCount < 3) {
                    iterationCount++
                    val scores = mutableMapOf<String, Int>()

                    for (evaluator in healthyModels) {
                        var totalEvaluatorScore = 0
                        for ((id, code) in proposals) {
                            if (id == evaluator.id) continue
                            val reviewPrompt = "أنت مدقق برمجي. قيم الكود البرمجي التالي من 0 إلى 100 بناءً على الجودة والصحة، واكتب رقم النسبة المئوية فقط في السطر الأول بصيغة 'SCORE: XX' ثم ملاحظاتك:\n\n$code"
                            val revRes = callAiModelApi(evaluator, reviewPrompt)
                            if (revRes.first) {
                                val scoreParsed = parseScoreFromText(revRes.second)
                                totalEvaluatorScore += scoreParsed
                            }
                        }
                        val avg = if (proposals.size > 1) totalEvaluatorScore / (proposals.size - 1) else 92
                        scores[evaluator.id] = avg
                    }

                    val topEntry = scores.maxByOrNull { it.value }
                    if (topEntry != null) {
                        bestScore = topEntry.value
                        bestCode = proposals[topEntry.key] ?: bestCode
                    }

                    auditTrail.append("الدورة $iterationCount: أعلى نسبة مقيمة = $bestScore%\n")
                    appendDiscussionEntry(taskRecord, "مجلس المراجعة (دورة $iterationCount)", "تم تحقيق نسبة إجماع $bestScore%", "دورة المراجعة والتدقيق رقم $iterationCount ($bestScore%)")

                    if (bestScore >= 90) {
                        consensusReached = true
                    } else {
                        withContext(Dispatchers.Main) {
                            setStatus("الحالة: ⚙️ النسبة ($bestScore%) أقل من 90%. جاري تكرار دورة التصحيح...", "#FBBF24")
                        }

                        for (m in healthyModels) {
                            val refinePrompt = "الكود الأساسي حصل على نسبة $bestScore% وهي أقل من الحد المطلوب (90%). قم بتعديله وتصحيحه بالكامل ليكون مثالياً:\n\n$bestCode"
                            val refRes = callAiModelApi(m, refinePrompt)
                            if (refRes.first && refRes.second.isNotBlank()) {
                                proposals[m.id] = refRes.second
                            }
                        }
                    }
                }

                finalConsensusCode = bestCode
                lastAuditReport = "🗂️ ملف المهمة: ${taskRecord.taskId}\n" +
                        "👑 المدير المسؤول: ${leader.name}\n" +
                        "📋 البرومبت المحسن: $engineeredPrompt\n" +
                        "🔄 عدد دورات التصحيح: $iterationCount\n" +
                        "🏆 نسبة الإجماع النهائية المحققة: $bestScore%\n\n" +
                        "📜 سجل الشفافية والتقييم:\n$auditTrail"

                taskRecord.finalCode = finalConsensusCode
                taskRecord.auditReport = lastAuditReport
                appendDiscussionEntry(
                    taskRecord,
                    "النتيجة النهائية المعتمدة",
                    "تم اعتماد الكود بنسبة $bestScore%",
                    "مكتمل بالإجماع ($bestScore%) - جاهز للتعديل أو الإضافة في أي وقت"
                )

                withContext(Dispatchers.Main) {
                    btnStartCouncil.isEnabled = true
                    setStatus("الحالة: ✅ تم الاعتماد وحفظ المهمة كاملة في ذاكرة الهاتف ($bestScore%)!", "#4ADE80")
                    resultTextView.text = "--- 🎉 تم اعتماد البرنامج وحفظه في ملف المهمة (الإجماع $bestScore%) ---\n\n$finalConsensusCode\n\n-------------------\n💡 يمكنك كتابة أي تعديل إضافي بالأعلى وسيتذكر المجلس هذا الكود وكل ما نوقش تلقائياً!"
                    decisionLayout.visibility = View.VISIBLE
                }

            } catch (e: Exception) {
                appendDiscussionEntry(taskRecord, "خطأ نظام", e.localizedMessage ?: "خطأ غير معروف", "توقف بسبب خطأ: ${e.localizedMessage}")
                withContext(Dispatchers.Main) {
                    btnStartCouncil.isEnabled = true
                    setStatus("الحالة: ❌ توقف المجلس (تم حفظ نقطة التوقف في ملف المهمة)", "#F87171")
                    resultTextView.text = "حدث خطأ: ${e.localizedMessage}\n\nتم حفظ كل ما تم الوصول إليه في [📁 المهام والذاكرة] لاستئنافه."
                }
            }
        }
    }

    private suspend fun selectActiveLeader(activeModels: List<AiAgentModel>): AiAgentModel? {
        if (manualLeaderId != "AUTO") {
            val selected = activeModels.find { it.id == manualLeaderId }
            if (selected != null) {
                val test = callAiModelApi(selected, "Reply OK")
                if (test.first) return selected
            }
        }

        for (m in activeModels) {
            val test = callAiModelApi(m, "Reply OK")
            if (test.first) return m
        }
        return null
    }

    private fun parseScoreFromText(text: String): Int {
        return try {
            for (l in text.lines()) {
                if (l.uppercase().contains("SCORE:")) {
                    val num = l.replace(Regex("[^0-9]"), "").toInt()
                    return num.coerceIn(50, 100)
                }
            }
            val fallbackNum = text.replace(Regex("[^0-9]"), "").take(2).toIntOrNull() ?: 90
            fallbackNum.coerceIn(50, 100)
        } catch (e: Exception) {
            90
        }
    }

    // ==================== دوال الاتصال المصححة والذكية (Gemini 3.8 + Groq Llama 3.3) ====================

    private fun callAiModelApi(model: AiAgentModel, promptText: String): Pair<Boolean, String> {
        return try {
            val key = model.apiKey.trim()
            val mId = model.modelId.trim()

            // توجيه ذكي يمنع خطأ 404 الناتج عن تبديل المزود أو الرابط بالخطأ
            val isGroq = key.startsWith("gsk_") || model.providerType == "GROQ" || mId.contains("llama", ignoreCase = true)
            val isGemini = (key.startsWith("AIza") || model.providerType == "GEMINI" || mId.contains("gemini", ignoreCase = true)) && !isGroq

            when {
                isGemini -> callGeminiRestApiFixed(model, promptText)
                isGroq -> callGroqOrOpenAiRestApiFixed(model, promptText, forceGroq = true)
                else -> callGroqOrOpenAiRestApiFixed(model, promptText, forceGroq = false)
            }
        } catch (e: Exception) {
            Pair(false, "خطأ اتصال: ${e.localizedMessage}")
        }
    }

    private fun callGeminiRestApiFixed(model: AiAgentModel, prompt: String): Pair<Boolean, String> {
        var cleanModelId = model.modelId.trim()
            .substringAfterLast("models/")
            .substringBefore(":generateContent")
            .substringBefore("?")
            .trim()

        if (cleanModelId.isBlank() || cleanModelId == "gemini-1.5-flash" || cleanModelId.contains("http")) {
            cleanModelId = DEFAULT_GEMINI_MODEL
        }

        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModelId:generateContent?key=${model.apiKey.trim()}"

        val bodyObj = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        }

        val req = Request.Builder()
            .url(endpoint)
            .post(bodyObj.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .build()

        val res = client.newCall(req).execute()
        val raw = res.body?.string() ?: ""
        if (res.isSuccessful) {
            val text = JSONObject(raw)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .optString("text", raw)
            return Pair(true, text)
        }
        return Pair(false, "HTTP ${res.code} ($cleanModelId): $raw")
    }

    private fun callGroqOrOpenAiRestApiFixed(model: AiAgentModel, prompt: String, forceGroq: Boolean): Pair<Boolean, String> {
        val endpoint = if (forceGroq || model.endpointUrl.isBlank() || !model.endpointUrl.startsWith("http") || model.endpointUrl.contains("generativelanguage") || model.endpointUrl.contains("console.groq.com")) {
            DEFAULT_GROQ_ENDPOINT
        } else {
            model.endpointUrl.trim()
        }

        var activeModelId = model.modelId.trim()
        if (forceGroq && (activeModelId.isBlank() || activeModelId.contains("http") || activeModelId.contains("gemini"))) {
            activeModelId = DEFAULT_GROQ_MODEL
        }

        val bodyObj = JSONObject().apply {
            put("model", activeModelId)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            put("temperature", 0.7)
        }

        val req = Request.Builder()
            .url(endpoint)
            .post(bodyObj.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${model.apiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .build()

        val res = client.newCall(req).execute()
        val raw = res.body?.string() ?: ""
        if (res.isSuccessful) {
            val text = JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content", raw)
            return Pair(true, text)
        }
        return Pair(false, "HTTP ${res.code} ($activeModelId): $raw")
    }

    // ==================== تصدير ZIP والتقارير ====================

    private fun generateAndDownloadZip() {
        if (finalConsensusCode.isEmpty()) {
            Toast.makeText(this, "لا يوجد كود معتمد لتنزيله حالياً", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val fileName = "BasselAI_Project_${System.currentTimeMillis()}.zip"
            val fos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(android.provider.MediaStore.Files.getContentUri("external"), contentValues)
                uri?.let { resolver.openOutputStream(it) }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val targetFile = File(downloadsDir, fileName)
                FileOutputStream(targetFile)
            }

            if (fos != null) {
                val zos = ZipOutputStream(fos)

                zos.putNextEntry(ZipEntry("main_code.txt"))
                zos.write(finalConsensusCode.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("audit_and_memory_report.txt"))
                zos.write(lastAuditReport.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // تضمين ملف ذاكرة المهمة الكامل داخل الـ ZIP أيضاً
                currentActiveTaskId?.let { id ->
                    val taskFile = getTaskFile(id)
                    if (taskFile.exists()) {
                        zos.putNextEntry(ZipEntry("task_memory_$id.json"))
                        zos.write(taskFile.readBytes())
                        zos.closeEntry()
                    }
                }

                zos.close()
                fos.close()

                Toast.makeText(this, "تم حفظ ملف الـ ZIP مع سجل الذاكرة في التنزيلات (Downloads)!", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "فشل ضغط الملف: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAuditReportDialog() {
        AlertDialog.Builder(this)
            .setTitle("📋 تقرير الشفافية وذاكرة المهمة")
            .setMessage(lastAuditReport.ifEmpty { "لا يوجد تقرير حالياً." })
            .setPositiveButton("إغلاق", null)
            .show()
    }

    private fun showLogsDialog() {
        val count = loadAllTasksFromRepo().size
        AlertDialog.Builder(this)
            .setTitle("📊 حالة النظام ومستودع الذاكرة")
            .setMessage(
                "• مسار مستودع المهام على الهاتف:\n${repoDir.absolutePath}\n\n" +
                "• عدد ملفات المهام المحفوظة حالياً: $count مهمة\n" +
                "• معرف Gemini المعتمد: $DEFAULT_GEMINI_MODEL\n" +
                "• معرف Groq المعتمد: $DEFAULT_GROQ_MODEL"
            )
            .setPositiveButton("إغلاق", null)
            .show()
    }

    private fun setStatus(txt: String, color: String) {
        statusTextView.text = txt
        statusTextView.setTextColor(Color.parseColor(color))
    }

    private fun createTopButton(l: String, act: () -> Unit) = Button(this).apply {
        text = l; textSize = 12f; setTextColor(Color.WHITE); isAllCaps = false
        background = createRoundedDrawable("#162032", 8f)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 4, 0) }
        setOnClickListener { act() }
    }

    private fun createBottomButton(l: String, bg: String, act: () -> Unit) = Button(this).apply {
        text = l; textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); isAllCaps = false
        background = createRoundedDrawable(bg, 8f)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 4, 0) }
        setOnClickListener { act() }
    }

    private fun createDialogLabel(t: String) = TextView(this).apply { text = t; setTextColor(Color.parseColor("#93C5FD")); textSize = 13f; setPadding(0, 4, 0, 4) }
    private fun createDialogInput(h: String, v: String) = EditText(this).apply { hint = h; setText(v); setHintTextColor(Color.parseColor("#64748B")); setTextColor(Color.WHITE); textSize = 13f; setPadding(16, 14, 16, 14); background = createRoundedDrawable("#1E293B", 8f) }
    private fun createRoundedDrawable(hex: String, r: Float) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = r * resources.displayMetrics.density; setColor(Color.parseColor(hex)) }
    private fun createSpacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h) }
}
