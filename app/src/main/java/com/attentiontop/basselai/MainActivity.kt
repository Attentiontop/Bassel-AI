package com.attentiontop.basselai

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : Activity() {

    enum class ModelStatus { AVAILABLE, UNAVAILABLE, FROZEN, MANUALLY_STOPPED, RUNNING, FAILED }
    enum class ConversationState { ACTIVE, PAUSED, DELETING }

    data class AiModel(
        val id: String,
        var name: String,
        var provider: String,
        var endpoint: String,
        var modelId: String,
        var encryptedKey: String,
        var role: String,
        var status: ModelStatus = ModelStatus.AVAILABLE
    )

    data class ConversationRecord(
        val conversationId: String,
        var title: String,
        val createdAt: String,
        var updatedAt: String,
        var state: ConversationState = ConversationState.ACTIVE,
        var stoppingPoint: String,
        var engineeredPrompt: String,
        var finalCode: String,
        var auditReport: String,
        var reviewScore: Int?,
        val messagesHistory: JSONArray
    )

    private data class Proposal(val model: AiModel, val text: String, val error: String?)
    private data class ReviewerReport(val model: AiModel, val text: String, val error: String?)
    private data class CandidateScore(val proposal: Proposal, val scores: List<Int>, val average: Double?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences("bassel_ai_secure_v4", Context.MODE_PRIVATE) }
    private lateinit var rootRepoDir: File

    private val models = mutableListOf<AiModel>()
    private val conversationJobs = ConcurrentHashMap<String, Job>()
    private val activeHttpCalls = ConcurrentHashMap<String, okhttp3.Call>()

    private val conversationsList = mutableListOf<ConversationRecord>()
    private var currentConvId: String? = null
    private var manualLeaderId: String = "AUTO"
    private var latestZip: File? = null

    private lateinit var btnModeSelector: Button
    private lateinit var activeTaskBanner: TextView
    private lateinit var statusTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var promptInput: EditText
    private lateinit var btnPromptEngineer: Button
    private lateinit var btnReadinessCheck: Button
    private lateinit var btnStartCouncil: Button
    private lateinit var decisionLayout: LinearLayout
    private lateinit var resultTextView: TextView

    companion object {
        private const val REQUEST_EXPORT = 9202
        const val DEFAULT_GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        const val DEFAULT_OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        const val DEFAULT_GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta"
        const val DEFAULT_OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#090D16")
        window.navigationBarColor = Color.parseColor("#090D16")

        rootRepoDir = File(filesDir, "conversations_repository")
        if (!rootRepoDir.exists()) rootRepoDir.mkdirs()

        loadModels()
        loadAllConversations()
        buildMasterUi()

        if (conversationsList.isNotEmpty()) {
            openConversation(conversationsList.first().conversationId)
        } else {
            createNewConversation("محادثة رئيسية")
        }
        updateLeaderButtonLabel()
    }

    override fun onDestroy() {
        conversationJobs.values.forEach { it.cancel() }
        activeHttpCalls.values.forEach { it.cancel() }
        scope.cancel()
        super.onDestroy()
    }

    private fun buildMasterUi() {
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#090D16"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(dp(14), dp(14), dp(14), dp(10))
        }

        val titleText = TextView(this).apply {
            text = "مجلس الإدارة والوكلاء المتزامن - Bassel AI"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(10))
        }
        rootLayout.addView(titleText)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        topBar.addView(createTopButton("📁 المحادثات والأرشيف") { showConversationsManagerDialog() })
        topBar.addView(createTopButton("📊 السجل") { showLogsAndStatesDialog() })
        topBar.addView(createTopButton("⚙️ المفاتيح والنماذج") { showModelsManagerDialog() })
        rootLayout.addView(topBar)

        rootLayout.addView(createSpacer(8))

        btnModeSelector = Button(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#60A5FA"))
            background = createRoundedDrawable("#162032", 12f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isAllCaps = false
            setOnClickListener { showLeaderSelectorDialog() }
        }
        rootLayout.addView(btnModeSelector)

        rootLayout.addView(createSpacer(6))

        val bannerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        activeTaskBanner = TextView(this).apply {
            text = "📂 المحادثة الحالية: [جديد]"
            textSize = 11f
            setTextColor(Color.parseColor("#A7F3D0"))
            background = createRoundedDrawable("#064E3B", 8f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2.2f)
        }
        val btnPauseResume = Button(this).apply {
            text = "⏸️ إيقاف مؤقت"
            textSize = 10.5f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = createRoundedDrawable("#1E3A8A", 8f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.8f).apply { marginStart = dp(4) }
            setOnClickListener { togglePauseResumeCurrentConversation() }
        }
        bannerRow.addView(activeTaskBanner)
        bannerRow.addView(btnPauseResume)
        rootLayout.addView(bannerRow)

        statusTextView = TextView(this).apply {
            text = "الحالة: جاهز تماماً."
            textSize = 12.5f
            setTextColor(Color.parseColor("#4ADE80"))
            setPadding(dp(4), dp(6), dp(4), dp(4))
        }
        rootLayout.addView(statusTextView)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
        }
        rootLayout.addView(progressBar)

        promptInput = EditText(this).apply {
            hint = "اكتب فكرة مشروعك هنا أو استكمل النقاش..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 13.5f
            minLines = 3
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = createRoundedDrawable("#131B2B", 12f)
            setTextIsSelectable(true)
        }
        rootLayout.addView(promptInput)

        rootLayout.addView(createSpacer(6))

        btnPromptEngineer = Button(this).apply {
            text = "✨ صياغة وتحسين الطلب بواسطة Prompt Engineer"
            textSize = 11.5f
            setTextColor(Color.parseColor("#E2E8F0"))
            isAllCaps = false
            background = createRoundedDrawable("#1E293B", 8f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36))
            setOnClickListener { openPromptEngineerDialog() }
        }
        rootLayout.addView(btnPromptEngineer)

        rootLayout.addView(createSpacer(6))

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        btnReadinessCheck = Button(this).apply {
            text = "🔍 فحص جاهزية الكل"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = createRoundedDrawable("#1E3A8A", 10f)
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(4) }
            setOnClickListener { runPreflight() }
        }

        btnStartCouncil = Button(this).apply {
            text = "🚀 بدء المجلس والعمل في الخفاء"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = createRoundedDrawable("#2563EB", 10f)
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(4) }
            setOnClickListener { startCouncilWorkflow() }
        }

        actionRow.addView(btnReadinessCheck)
        actionRow.addView(btnStartCouncil)
        rootLayout.addView(actionRow)

        decisionLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }

        val btnCreateZip = Button(this).apply {
            text = "📦 إنشاء ZIP"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = createRoundedDrawable("#0D9488", 8f)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(3) }
            setOnClickListener { createZip(autoExport = false) }
        }

        val btnExportZip = Button(this).apply {
            text = "📥 تصدير للـ Downloads"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = createRoundedDrawable("#16A34A", 8f)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
            setOnClickListener { createZip(autoExport = true) }
        }

        val btnViewAudit = Button(this).apply {
            text = "📋 التقرير والذاكرة"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = createRoundedDrawable("#475569", 8f)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(3) }
            setOnClickListener { showAuditAndMemoryHubDialog() }
        }

        decisionLayout.addView(btnCreateZip)
        decisionLayout.addView(btnExportZip)
        decisionLayout.addView(btnViewAudit)
        rootLayout.addView(decisionLayout)

        rootLayout.addView(createSpacer(8))

        val outputScroll = ScrollView(this).apply {
            background = createRoundedDrawable("#0E1420", 12f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        resultTextView = TextView(this).apply {
            text = "--- 📊 نظام Bassel AI مهيأ ومؤمن ---\n\nاختر محادثة أو أضف نماذج للبدء."
            textSize = 13f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setLineSpacing(6f, 1.1f)
            setTextIsSelectable(true)
        }
        outputScroll.addView(resultTextView)
        rootLayout.addView(outputScroll)

        rootLayout.addView(createSpacer(8))

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        bottomBar.addView(createBottomButton("📋 نسخ", "#162032") { showQuickCopyMenuDialog() })
        bottomBar.addView(createBottomButton("📤 مشاركة", "#162032") {
            val conv = getCurrentConversation()
            val textToShare = conv?.finalCode?.ifBlank { resultTextView.text.toString() } ?: resultTextView.text.toString()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, textToShare)
            }
            startActivity(Intent.createChooser(intent, "مشاركة عبر"))
        })
        bottomBar.addView(createBottomButton("➕ محادثة جديدة", "#312E81") {
            createNewConversation("محادثة ${conversationsList.size + 1}")
        })
        rootLayout.addView(bottomBar)

        setContentView(rootLayout)
    }

    private fun loadAllConversations() {
        conversationsList.clear()
        val dirs = rootRepoDir.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (d in dirs) {
            val jsonFile = File(d, "conv_meta.json")
            if (jsonFile.exists()) {
                try {
                    val o = JSONObject(jsonFile.readText(StandardCharsets.UTF_8))
                    val conv = ConversationRecord(
                        conversationId = o.getString("conversationId"),
                        title = o.optString("title", "محادثة"),
                        createdAt = o.optString("createdAt", ""),
                        updatedAt = o.optString("updatedAt", ""),
                        state = try { ConversationState.valueOf(o.optString("state", "ACTIVE")) } catch (_: Exception) { ConversationState.ACTIVE },
                        stoppingPoint = o.optString("stoppingPoint", "جاهز"),
                        engineeredPrompt = o.optString("engineeredPrompt", ""),
                        finalCode = o.optString("finalCode", ""),
                        auditReport = o.optString("auditReport", ""),
                        reviewScore = if (!o.isNull("reviewScore")) o.optInt("reviewScore") else null,
                        messagesHistory = o.optJSONArray("messagesHistory") ?: JSONArray()
                    )
                    conversationsList.add(conv)
                } catch (_: Exception) {}
            }
        }
        conversationsList.sortByDescending { it.updatedAt }
    }

    private fun saveConversationToFile(conv: ConversationRecord) {
        try {
            val d = File(rootRepoDir, conv.conversationId)
            if (!d.exists()) d.mkdirs()
            val jsonFile = File(d, "conv_meta.json")
            val obj = JSONObject().apply {
                put("conversationId", conv.conversationId)
                put("title", conv.title)
                put("createdAt", conv.createdAt)
                put("updatedAt", conv.updatedAt)
                put("state", conv.state.name)
                put("stoppingPoint", conv.stoppingPoint)
                put("engineeredPrompt", conv.engineeredPrompt)
                put("finalCode", conv.finalCode)
                put("auditReport", conv.auditReport)
                put("reviewScore", conv.reviewScore ?: JSONObject.NULL)
                put("messagesHistory", conv.messagesHistory)
            }
            jsonFile.writeText(obj.toString(2), StandardCharsets.UTF_8)
        } catch (_: Exception) {}
    }

    private fun getCurrentConversation(): ConversationRecord? {
        return conversationsList.find { it.conversationId == currentConvId }
    }

    private fun createNewConversation(title: String): ConversationRecord {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val id = "conv_${System.currentTimeMillis()}"
        val record = ConversationRecord(
            conversationId = id,
            title = title,
            createdAt = now,
            updatedAt = now,
            state = ConversationState.ACTIVE,
            stoppingPoint = "جاهز للبدء",
            engineeredPrompt = "",
            finalCode = "",
            auditReport = "",
            reviewScore = null,
            messagesHistory = JSONArray()
        )
        conversationsList.add(0, record)
        saveConversationToFile(record)
        openConversation(id)
        return record
    }

    private fun openConversation(convId: String) {
        val conv = conversationsList.find { it.conversationId == convId } ?: return
        currentConvId = conv.conversationId
        promptInput.setText(conv.engineeredPrompt)
        activeTaskBanner.text = "📂 المحادثة: [${conv.title}] (${conv.state.name})"
        if (conv.finalCode.isNotBlank()) {
            resultTextView.text = conv.finalCode
            decisionLayout.visibility = View.VISIBLE
        } else {
            resultTextView.text = "--- المحادثة النشطة: ${conv.title} ---\nآخر نقطة توقف: ${conv.stoppingPoint}\n\nاكتب طلبك بالأسفل."
            decisionLayout.visibility = View.GONE
        }
        setStatus("تم فتح المحادثة: ${conv.title}", if (conv.state == ConversationState.ACTIVE) "#4ADE80" else "#FBBF24")
    }

    private fun togglePauseResumeCurrentConversation() {
        val conv = getCurrentConversation() ?: return
        if (conv.state == ConversationState.ACTIVE) {
            conv.state = ConversationState.PAUSED
            setStatus("المحادثة في حالة إيقاف مؤقت. لن تبدأ مراحل جديدة.", "#FBBF24")
            activeTaskBanner.text = "📂 المحادثة: [${conv.title}] (PAUSED)"
        } else {
            conv.state = ConversationState.ACTIVE
            setStatus("تم استئناف المحادثة بنجاح.", "#4ADE80")
            activeTaskBanner.text = "📂 المحادثة: [${conv.title}] (ACTIVE)"
        }
        saveConversationToFile(conv)
    }

    private fun showConversationsManagerDialog() {
        if (conversationsList.isEmpty()) createNewConversation("محادثة جديدة")
        val items = Array(conversationsList.size) { i ->
            val c = conversationsList[i]
            val tag = if (c.conversationId == currentConvId) "🟢 [نشطة] " else ""
            "$tag🗂️ ${c.title} (${c.state.name})"
        }
        AlertDialog.Builder(this)
            .setTitle("📁 إدارة المحادثات")
            .setItems(items) { _, which -> openConversation(conversationsList[which].conversationId) }
            .setPositiveButton("➕ محادثة جديدة") { _, _ -> createNewConversation("محادثة ${conversationsList.size + 1}") }
            .setNeutralButton("🗑️ حذف المحادثة الحالية") { _, _ ->
                getCurrentConversation()?.let { safeDeleteConversation(it) }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun safeDeleteConversation(conv: ConversationRecord) {
        val activeJob = conversationJobs[conv.conversationId]
        if (activeJob != null && activeJob.isActive) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ تحذير: مهام جارية")
                .setMessage("المحادثة تحتوى على عمليات جارية. هل تريد إلغاء العمليات وحذف المحادثة نهائياً؟")
                .setPositiveButton("إلغاء العمليات والحذف") { _, _ ->
                    activeJob.cancel()
                    conversationJobs.remove(conv.conversationId)
                    performActualDeletion(conv)
                }
                .setNegativeButton("تراجع", null)
                .show()
        } else {
            performActualDeletion(conv)
        }
    }

    private fun performActualDeletion(conv: ConversationRecord) {
        try {
            val d = File(rootRepoDir, conv.conversationId)
            if (d.exists()) {
                d.listFiles()?.forEach { it.delete() }
                d.delete()
            }
            conversationsList.remove(conv)
            currentConvId = null
            if (conversationsList.isNotEmpty()) {
                openConversation(conversationsList.first().conversationId)
            } else {
                createNewConversation("محادثة رئيسية")
            }
            toast("تم حذف المحادثة وتوابعها بأمان.")
        } catch (e: Exception) {
            toast("فشل الحذف التام: ${safeError(e)}")
        }
    }
    private fun openPromptEngineerDialog() {
        val raw = promptInput.text.toString().trim()
        if (raw.isBlank()) {
            toast("اكتب طلباً أولاً.")
            return
        }
        val activeModels = models.filter { decryptKey(it.encryptedKey).isNotBlank() && it.status == ModelStatus.AVAILABLE }
        val agent = activeModels.firstOrNull { it.role == "Prompt Engineer" } ?: activeModels.firstOrNull()
        if (agent == null) {
            toast("لا توجد نماذج متاحة.")
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val refinedEdit = createDialogInput("جاري صياغة الطلب...", "").apply {
            minLines = 6
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
        }
        layout.addView(refinedEdit)

        val dialog = AlertDialog.Builder(this)
            .setTitle("✨ صياغة الطلب عبر ${agent.name}")
            .setView(layout)
            .setPositiveButton("اعتماد") { _, _ ->
                val txt = refinedEdit.text.toString().trim()
                if (txt.isNotBlank()) {
                    promptInput.setText(txt)
                    getCurrentConversation()?.let {
                        it.engineeredPrompt = txt
                        saveConversationToFile(it)
                    }
                }
            }
            .setNegativeButton("إغلاق", null)
            .create()
        dialog.show()

        scope.launch {
            setBusy(true, "جاري الصياغة...")
            try {
                val res = withContext(Dispatchers.IO) {
                    callModelApiDirect(agent, "أعد صياغة الطلب التالي بوضوح واحترافية بدون زيادة متطلبات جديدة:\n\n$raw")
                }
                refinedEdit.setText(res)
            } catch (e: Exception) {
                refinedEdit.setText("خطأ: ${safeError(e)}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun runPreflight() {
        val activeModels = models.filter { decryptKey(it.encryptedKey).isNotBlank() }
        if (activeModels.isEmpty()) {
            toast("لا توجد مفاتيح صالحة.")
            return
        }
        btnReadinessCheck.isEnabled = false
        setBusy(true, "🔍 جاري فحص النماذج...", "#38BDF8")

        scope.launch {
            val results = activeModels.map { m ->
                async(Dispatchers.IO) {
                    val start = System.currentTimeMillis()
                    try {
                        withTimeout(15_000) { callModelApiDirect(m, "READY") }
                        val ms = System.currentTimeMillis() - start
                        if (m.status != ModelStatus.FROZEN && m.status != ModelStatus.MANUALLY_STOPPED) {
                            m.status = ModelStatus.AVAILABLE
                        }
                        Triple(m, "جاهز (${ms}ms)", true)
                    } catch (e: Exception) {
                        if (m.status != ModelStatus.FROZEN && m.status != ModelStatus.MANUALLY_STOPPED) {
                            m.status = ModelStatus.UNAVAILABLE
                        }
                        Triple(m, safeError(e), false)
                    }
                }
            }.awaitAll()

            val sb = StringBuilder("--- نتائج الفحص ---\n\n")
            results.forEach { (m, msg, ok) ->
                sb.append(if (ok) "✅ " else "❌ ").append("${m.name}: $msg\n")
            }
            resultTextView.text = sb.toString()
            setBusy(false, "انتهى الفحص.")
            btnReadinessCheck.isEnabled = true
            saveModels()
        }
    }

    private fun startCouncilWorkflow() {
        val conv = getCurrentConversation() ?: return
        if (conv.state == ConversationState.PAUSED) {
            toast("المحادثة موقوفة مؤقتاً.")
            return
        }
        val rawPrompt = promptInput.text.toString().trim()
        if (rawPrompt.isBlank()) {
            toast("أدخل الطلب أولاً.")
            return
        }

        if (conversationJobs[conv.conversationId]?.isActive == true) {
            toast("هناك مهمة جارية بالفعل في هذه المحادثة.")
            return
        }

        val activeModels = models.filter {
            decryptKey(it.encryptedKey).isNotBlank() &&
            it.status == ModelStatus.AVAILABLE
        }
        if (activeModels.isEmpty()) {
            toast("لا توجد نماذج متاحة وغير مجمدة.")
            return
        }

        decisionLayout.visibility = View.GONE
        conv.engineeredPrompt = rawPrompt
        conv.updatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        saveConversationToFile(conv)

        val job = scope.launch {
            btnStartCouncil.isEnabled = false
            setBusy(true, "المرحلة 1/5: فحص الاتصال...", "#38BDF8")
            try {
                if (conv.state == ConversationState.PAUSED) throw CancellationException("المحادثة أُوقفت مؤقتاً.")

                val healthyModels = activeModels.filter { m ->
                    try {
                        withTimeout(10_000) { callModelApiDirect(m, "READY") }
                        true
                    } catch (_: Exception) { false }
                }
                if (healthyModels.isEmpty()) throw IllegalStateException("لم يستجب أي نموذج.")

                val leader = manualLeaderId.let { id ->
                    if (id != "AUTO") healthyModels.find { it.id == id } else null
                } ?: healthyModels.firstOrNull { it.role == "Planner/Leader" } ?: healthyModels.first()

                setStatus("المرحلة 2/5: وضع الخطة عبر ${leader.name}...", "#38BDF8")
                val plan = withTimeout(45_000) {
                    callModelApiDirect(leader, "ضع خطة تنفيذ موجزة ومعايير قبول:\n$rawPrompt")
                }

                if (conv.state == ConversationState.PAUSED) throw CancellationException("تم الإيقاف المؤقت.")

                setStatus("المرحلة 3/5: التوليد المتوازي للمقترحات...", "#38BDF8")
                val proposals = healthyModels.map { m ->
                    async(Dispatchers.IO) {
                        try {
                            val text = withTimeout(60_000) {
                                callModelApiDirect(m, "نفّذ المطلوب وضع الكود في كتل fenced code:\n$rawPrompt\n\nالخطة:\n$plan")
                            }
                            Proposal(m, text, null)
                        } catch (e: Exception) {
                            Proposal(m, "", safeError(e))
                        }
                    }
                }.awaitAll().filter { it.error == null && it.text.isNotBlank() }

                if (proposals.isEmpty()) throw IllegalStateException("فشل توليد أي مقترح.")

                setStatus("المرحلة 4/5: المراجعة وتقييم المرشحين...", "#FBBF24")
                val reviews = healthyModels.map { revModel ->
                    async(Dispatchers.IO) {
                        try {
                            val revPrompt = buildString {
                                append("قيّم كل مقترح وأعط درجات بصيغة CANDIDATE_<رقم>_SCORE: <رقم من 0 إلى 100>.\n\n")
                                proposals.forEachIndexed { idx, p ->
                                    append("=== CANDIDATE_${idx + 1} (${p.model.name}) ===\n${p.text.take(6000)}\n\n")
                                }
                            }
                            ReviewerReport(revModel, callModelApiDirect(revModel, revPrompt), null)
                        } catch (e: Exception) {
                            ReviewerReport(revModel, "", safeError(e))
                        }
                    }
                }.awaitAll().filter { it.error == null }

                val scored = proposals.mapIndexed { idx, prop ->
                    val candNo = idx + 1
                    val scores = reviews.mapNotNull { parseScoreFromReview(it.text, candNo) }
                    CandidateScore(prop, scores, if (scores.isNotEmpty()) scores.average() else null)
                }

                val best = scored.maxByOrNull { it.average ?: -1.0 }
                val finalScore = best?.average?.toInt()

                setStatus("المرحلة 5/5: الدمج والتنقيح النهائي...", "#38BDF8")
                val digest = reviews.joinToString("\n") { it.text.take(1500) }
                val finalRes = withContext(Dispatchers.IO) {
                    callModelApiDirect(leader, "أعط الحل النهائي المدمج:\n${best?.proposal?.text ?: ""}\n\nالمراجعات:\n$digest")
                }

                conv.finalCode = finalRes
                conv.reviewScore = finalScore
                conv.stoppingPoint = if (finalScore != null) "مكتمل ($finalScore/100)" else "مكتمل (بدون تقييم رقمي)"
                conv.auditReport = "القائد: ${leader.name}\nالتقييم: ${finalScore?.let { "$it/100" } ?: "غير متوفر"}"
                saveConversationToFile(conv)

                resultTextView.text = "--- النتيجة النهائية ---\n\n$finalRes"
                decisionLayout.visibility = View.VISIBLE
                setStatus("✅ اكتمل المجلس بنجاح.", "#4ADE80")

            } catch (e: CancellationException) {
                setStatus("تم إلغاء المهمة.", "#F87171")
            } catch (e: Exception) {
                setStatus("خطأ: ${safeError(e)}", "#F87171")
            } finally {
                btnStartCouncil.isEnabled = true
                setBusy(false)
                conversationJobs.remove(conv.conversationId)
            }
        }
        conversationJobs[conv.conversationId] = job
    }

    private fun parseScoreFromReview(text: String, candidateNumber: Int): Int? {
        val regex = Regex("(?im)^\\s*CANDIDATE_${candidateNumber}_SCORE\\s*:\\s*(100|[1-9]?\\d)\\s*$")
        return regex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)
    }

    private suspend fun callModelApiDirect(model: AiModel, prompt: String): String = withContext(Dispatchers.IO) {
        val key = decryptKey(model.encryptedKey).trim()
        require(key.isNotBlank()) { "مفتاح API غير موجود" }

        val endpoint = if (model.provider == "Gemini" && !model.endpoint.contains(":generateContent")) {
            model.endpoint.trimEnd('/') + "/models/${model.modelId}:generateContent"
        } else {
            model.endpoint.trim()
        }

        val body = if (model.provider == "Gemini") {
            JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        } else {
            JSONObject()
                .put("model", model.modelId.trim())
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        }

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")

        if (model.provider == "Gemini") {
            reqBuilder.addHeader("x-goog-api-key", key)
        } else {
            reqBuilder.addHeader("Authorization", "Bearer $key")
        }

        val requestId = UUID.randomUUID().toString()
        val call = client.newCall(reqBuilder.build())
        activeHttpCalls[requestId] = call

        try {
            val response = call.execute()
            val raw = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${raw.take(200)}")
            }
            val json = JSONObject(raw)
            if (model.provider == "Gemini") {
                json.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                    ?: throw IllegalStateException("استجابة Gemini فارغة.")
            } else {
                json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                    ?: throw IllegalStateException("استجابة غير صالحة.")
            }
        } finally {
            activeHttpCalls.remove(requestId)
        }
    }

    private fun showModelsManagerDialog() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val btnAdd = Button(this).apply {
            text = "➕ إضافة نموذج جديد"
            setTextColor(Color.WHITE)
            background = createRoundedDrawable("#2563EB", 10f)
            setOnClickListener { showModelEditor(null) }
        }
        container.addView(btnAdd)
        container.addView(createSpacer(10))

        models.forEach { m ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = createRoundedDrawable("#1E293B", 10f)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            }
            card.addView(TextView(this).apply {
                text = "${m.name} [${m.status}]"
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            })

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val editBtn = Button(this).apply {
                text = "تعديل"
                setOnClickListener { showModelEditor(m) }
            }
            val stopBtn = Button(this).apply {
                text = "إيقاف يدوي"
                setOnClickListener {
                    m.status = ModelStatus.MANUALLY_STOPPED
                    saveModels()
                    toast("تم إيقاف النماذج.")
                }
            }
            row.addView(editBtn)
            row.addView(stopBtn)
            card.addView(row)
            container.addView(card)
        }

        scroll.addView(container)
        AlertDialog.Builder(this).setTitle("إدارة النماذج").setView(scroll).setPositiveButton("إغلاق", null).show()
    }

    private fun showModelEditor(existing: AiModel?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        val nameF = createDialogInput("الاسم", existing?.name ?: "")
        val epF = createDialogInput("الرابط", existing?.endpoint ?: DEFAULT_GROQ_ENDPOINT)
        val midF = createDialogInput("معرف النموذج", existing?.modelId ?: "")
        val keyF = createDialogInput("مفتاح API", "").apply { inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD }

        box.addView(nameF); box.addView(epF); box.addView(midF); box.addView(keyF)

        AlertDialog.Builder(this)
            .setTitle("إعداد النموذج")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val enteredKey = keyF.text.toString().trim()
                val encKey = if (enteredKey.isNotBlank()) encryptKey(enteredKey) else existing?.encryptedKey.orEmpty()
                val m = AiModel(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = nameF.text.toString().trim(),
                    provider = "Groq",
                    endpoint = epF.text.toString().trim(),
                    modelId = midF.text.toString().trim(),
                    encryptedKey = encKey,
                    role = "Coder"
                )
                if (existing != null) {
                    val idx = models.indexOfFirst { it.id == existing.id }
                    if (idx >= 0) models[idx] = m
                } else models.add(m)
                saveModels()
            }
            .show()
    }

    private fun createZip(autoExport: Boolean) {
        val conv = getCurrentConversation() ?: return
        val source = conv.finalCode.ifBlank { resultTextView.text.toString() }
        scope.launch {
            setBusy(true, "إنشاء ZIP...")
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = getExternalFilesDir(null) ?: filesDir
                    val zip = File(dir, "BasselAI_${conv.conversationId}.zip")
                    ZipOutputStream(zip.outputStream().buffered()).use { zos ->
                        splitGeneratedFiles(source).forEach { (name, content) ->
                            val safeName = File(name).name
                            if (safeName.isNotBlank() && !safeName.contains("..")) {
                                zos.putNextEntry(ZipEntry(safeName))
                                zos.write(content.toByteArray(StandardCharsets.UTF_8))
                                zos.closeEntry()
                            }
                        }
                    }
                    zip
                }
                latestZip = file
                if (autoExport) exportZip() else toast("تم إنشاء ZIP محلياً.")
            } catch (e: Exception) {
                toast("فشل ZIP: ${safeError(e)}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun splitGeneratedFiles(text: String): List<Pair<String, String>> {
        val regex = Regex("(?s)```([^\\n`]*)\\n(.*?)```")
        val matches = regex.findAll(text).toList()
        if (matches.isEmpty()) return listOf("output.txt" to text)
        return matches.mapIndexed { i, m ->
            val h = m.groupValues[1].trim()
            val filename = Regex("[A-Za-z0-9_.-]+\\.[A-Za-z0-9]+").find(h)?.value ?: "file_${i + 1}.txt"
            filename to m.groupValues[2].trimEnd()
        }
    }

    private fun exportZip() {
        val zip = latestZip ?: return
        scope.launch {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    withContext(Dispatchers.IO) {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, "BasselAI_${System.currentTimeMillis()}.zip")
                            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw IOException("فشل إدراج الملف في التنزيلات.")
                        contentResolver.openOutputStream(uri)?.use { out ->
                            zip.inputStream().use { it.copyTo(out) }
                        }
                    }
                    toast("تم التصدير إلى التنزيلات بنجاح.")
                }
            } catch (e: Exception) {
                toast("فشل التصدير: ${safeError(e)}")
            }
        }
    }

    private fun safeError(e: Throwable): String {
        val raw = e.message ?: e.javaClass.simpleName
        return raw.replace(Regex("(?i)(api[_ -]?key|authorization|bearer)\\s*[:=]?\\s*\\S+", RegexOption.IGNORE_CASE), "$1=[redacted]")
            .take(200)
    }

    private fun encryptKey(value: String): String {
        if (value.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        } catch (_: Exception) { "" }
    }

    private fun decryptKey(value: String): String {
        if (value.isBlank()) return ""
        return try {
            val all = Base64.decode(value, Base64.NO_WRAP)
            val iv = all.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(all.copyOfRange(12, all.size)), StandardCharsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val alias = "BasselAI.MasterKey.AES"
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun saveModels() {
        val arr = JSONArray()
        models.forEach { m ->
            arr.put(JSONObject()
                .put("id", m.id)
                .put("name", m.name)
                .put("provider", m.provider)
                .put("endpoint", m.endpoint)
                .put("modelId", m.modelId)
                .put("encryptedKey", m.encryptedKey)
                .put("role", m.role)
                .put("status", m.status.name))
        }
        prefs.edit().putString("models_master_v4", arr.toString()).apply()
    }

    private fun loadModels() {
        models.clear()
        try {
            val raw = prefs.getString("models_master_v4", "[]")
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                models.add(AiModel(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    provider = o.getString("provider"),
                    endpoint = o.getString("endpoint"),
                    modelId = o.getString("modelId"),
                    encryptedKey = o.optString("encryptedKey"),
                    role = o.optString("role", "Coder"),
                    status = try { ModelStatus.valueOf(o.optString("status")) } catch (_: Exception) { ModelStatus.AVAILABLE }
                ))
            }
        } catch (_: Exception) {}

        if (models.isEmpty()) {
            models.add(AiModel("g1", "Groq Llama 3.3", "Groq", DEFAULT_GROQ_ENDPOINT, "llama-3.3-70b-versatile", "", "Planner/Leader"))
            saveModels()
        }
    }

    private fun setBusy(busy: Boolean, msg: String = "", color: String = "#38BDF8") {
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (msg.isNotBlank()) setStatus(msg, color)
    }

    private fun setStatus(txt: String, colorHex: String = "#4ADE80") {
        if (::statusTextView.isInitialized) {
            statusTextView.text = txt
            statusTextView.setTextColor(Color.parseColor(colorHex))
        }
    }

    private fun updateLeaderButtonLabel() {
        if (::btnModeSelector.isInitialized) {
            btnModeSelector.text = "👑 القائد: $manualLeaderId"
        }
    }

    private fun copyText(label: String, valStr: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, valStr))
        toast("تم النسخ.")
    }

    private fun createTopButton(l: String, act: () -> Unit) = Button(this).apply {
        text = l; textSize = 11f; setTextColor(Color.WHITE); setBackgroundDrawable(createRoundedDrawable("#162032", 8f))
        setOnClickListener { act() }
    }
    private fun createBottomButton(l: String, bg: String, act: () -> Unit) = Button(this).apply {
        text = l; textSize = 11.5f; setTextColor(Color.WHITE); setBackgroundDrawable(createRoundedDrawable(bg, 8f))
        setOnClickListener { act() }
    }
    private fun createDialogInput(h: String, v: String) = EditText(this).apply {
        hint = h; setText(v); setTextColor(Color.WHITE); setBackgroundDrawable(createRoundedDrawable("#1E293B", 8f))
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }
    private fun createRoundedDrawable(hex: String, r: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = r * resources.displayMetrics.density; setColor(Color.parseColor(hex))
    }
    private fun createSpacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h)) }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun showLogsAndStatesDialog() { showSelectableTextDialog("السجل", "الحالة المستقرة للنظام تعمل.") }
    private fun showSelectableTextDialog(t: String, c: String) {
        AlertDialog.Builder(this).setTitle(t).setMessage(c).setPositiveButton("إغلاق", null).show()
    }
    private fun showLeaderSelectorDialog() {
        val opts = arrayOf("تلقائي ذكي (AUTO)") + models.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("اختيار القائد").setItems(opts) { _, which ->
            manualLeaderId = if (which == 0) "AUTO" else models[which - 1].id
            updateLeaderButtonLabel()
        }.show()
    }
    private fun showAuditAndMemoryHubDialog() {
        val conv = getCurrentConversation() ?: return
        showSelectableTextDialog("تقرير الشفافية والذاكرة", conv.auditReport.ifBlank { "لا توجد تقارير مسجلة بعد." })
    }
    private fun showQuickCopyMenuDialog() {
        val conv = getCurrentConversation()
        copyText("الكود النهائي", conv?.finalCode ?: resultTextView.text.toString())
    }
}
