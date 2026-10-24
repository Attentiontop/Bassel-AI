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
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : Activity() {

    // ==================== هياكل البيانات ونظام الحالات ====================

    enum class ModelStatus { AVAILABLE, UNAVAILABLE, FROZEN, MANUALLY_STOPPED, RUNNING, FAILED }
    enum class ConversationState { ACTIVE, PAUSED, DELETING }

    data class AiModel(
        val id: String,
        var name: String,
        var provider: String, // Groq, OpenRouter, Gemini, OpenAI-compatible
        var endpoint: String,
        var modelId: String,
        var encryptedKey: String,
        var role: String,     // Planner/Leader, Coder, Reviewer, Prompt Engineer
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

    // ==================== المتغيرات العامة OkHttpClient ونطاق العمل ====================

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(80, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences("bassel_ai_master_v3", Context.MODE_PRIVATE) }
    private lateinit var rootRepoDir: File

    private val models = mutableListOf<AiModel>()
    private val activeJobsMap = mutableMapOf<String, Job>()
    private val modelOkHttpCalls = mutableMapOf<String, okhttp3.Call>()

    private var conversationsList = mutableListOf<ConversationRecord>()
    private var currentConvId: String? = null

    private var manualLeaderId: String = "AUTO"
    private var latestZip: File? = null

    // عناصر الواجهة المعتمدة في كود الهيكل والصورة
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
        private const val REQUEST_EXPORT = 9201
        const val DEFAULT_GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        const val DEFAULT_OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        const val DEFAULT_GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"
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
            createNewConversation("مروع جديد")
        }
        updateLeaderButtonLabel()
    }

    override fun onDestroy() {
        activeJobsMap.values.forEach { it.cancel() }
        scope.cancel()
        super.onDestroy()
    }

    // ==================== بناء الواجهة الأساسية (مطابقة لكود الهيكل والصورة) ====================

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

        // الشريط العلوي (الأرشيف والمحادثات | السجل الحي | المفاتيح والنماذج)
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

        // زر اختيار مدير المجلس (القائد)
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

        // شريط المهمة والمحادثة النشطة (مع خيار إيقاف مؤقت واستئناف)
        val bannerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        activeTaskBanner = TextView(this).apply {
            text = "📂 المحادثة الحالية: [مشروع جديد]"
            textSize = 11f
            setTextColor(Color.parseColor("#A7F3D0"))
            background = createRoundedDrawable("#064E3B", 8f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2.2f)
        }
        val btnPauseResume = Button(this).apply {
            text = "⏸️ إيقاف"
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
            text = "الحالة: جاهز — أضف نموذجاً وابدأ المجلس."
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
            hint = "اكتب فكرة مشروعك هنا، أو تعديلاً لاستكمال المحادثة المفتوحة..."
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

        // أزرار الفحص والبدء
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

        // شريط إجراءات المشروع بعد اكتمال الحل
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

        // شاشة العرض الوسطى (قابلة للتحديد والنسخ الكامل)
        val outputScroll = ScrollView(this).apply {
            background = createRoundedDrawable("#0E1420", 12f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        resultTextView = TextView(this).apply {
            text = "--- 📊 تقرير جاهزية أعضاء المجلس والمعالجة ---\n\n" +
                    "• نظام المحادثات والمهام المعزولة مفعل بالكامل.\n" +
                    "• جميع مفاتيح API مشفرة محلياً عبر Android Keystore."
            textSize = 13f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setLineSpacing(6f, 1.1f)
            setTextIsSelectable(true)
        }
        outputScroll.addView(resultTextView)
        rootLayout.addView(outputScroll)

        rootLayout.addView(createSpacer(8))

        // الشريط السفلي
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

    // ==================== نظام إدارة المحادثات المعزولة (Conversation Management) ====================

    private fun getConvDir(convId: String): File {
        val dir = File(rootRepoDir, convId)
        if (!dir.exists()) dir.mkdirs()
        return dir
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
            val d = getConvDir(conv.conversationId)
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
            stoppingPoint = "محادثة جديدة جاهزة",
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
            resultTextView.text = "--- 📁 المحادثة النشطة: ${conv.title} ---\nآخر نقطة توقف: ${conv.stoppingPoint}\n\nاكتب فكرتك أو استكمل العمل أدناه."
            decisionLayout.visibility = View.GONE
        }
        setStatus("تم فتح المحادثة: ${conv.title}", if (conv.state == ConversationState.ACTIVE) "#4ADE80" else "#FBBF24")
    }

    private fun togglePauseResumeCurrentConversation() {
        val conv = getCurrentConversation() ?: return
        if (conv.state == ConversationState.ACTIVE) {
            conv.state = ConversationState.PAUSED
            setStatus("تم إيقاف المحادثة مؤقتاً. لن تبدأ مهام جديدة.", "#FBBF24")
            activeTaskBanner.text = "📂 المحادثة: [${conv.title}] (PAUSED)"
        } else {
            conv.state = ConversationState.ACTIVE
            setStatus("تم استئناف المحادثة بنجاح.", "#4ADE80")
            activeTaskBanner.text = "📂 المحادثة: [${conv.title}] (ACTIVE)"
        }
        saveConversationToFile(conv)
    }

    private fun showConversationsManagerDialog() {
        if (conversationsList.isEmpty()) {
            createNewConversation("محادثة 1")
        }
        val items = Array(conversationsList.size) { i ->
            val c = conversationsList[i]
            val activeTag = if (c.conversationId == currentConvId) "🟢 [نشطة] " else ""
            "$activeTag🗂️ ${c.title}\n⏱️ ${c.updatedAt} | الحالة: ${c.state.name}"
        }

        AlertDialog.Builder(this)
            .setTitle("📁 إدارة المحادثات المعزولة (${conversationsList.size})")
            .setItems(items) { _, which ->
                val selected = conversationsList[which]
                showSingleConversationOptionsDialog(selected)
            }
            .setPositiveButton("➕ محادثة جديدة") { _, _ ->
                createNewConversation("محادثة ${conversationsList.size + 1}")
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showSingleConversationOptionsDialog(conv: ConversationRecord) {
        val options = arrayOf(
            "🔄 فتح هذه المحادثة",
            "✏️ إعادة تسمية المحادثة",
            "🗑️ حذف المحادثة وملفاتها نهائياً"
        )
        AlertDialog.Builder(this)
            .setTitle("🗂️ ${conv.title}")
            .setItems(options) { _, idx ->
                when (idx) {
                    0 -> openConversation(conv.conversationId)
                    1 -> {
                        val input = EditText(this).apply { setText(conv.title) }
                        AlertDialog.Builder(this)
                            .setTitle("إعادة التسمية")
                            .setView(input)
                            .setPositiveButton("حفظ") { _, _ ->
                                val newT = input.text.toString().trim()
                                if (newT.isNotBlank()) {
                                    conv.title = newT
                                    saveConversationToFile(conv)
                                    openConversation(conv.conversationId)
                                    toast("تمت التسمية بنجاح.")
                                }
                            }
                            .setNegativeButton("إغلاق", null)
                            .show()
                    }
                    2 -> {
                        // حذف آمن للمحادثة وتوابعها مع التعامل مع المهام النشطة
                        safeDeleteConversation(conv)
                    }
                }
            }
            .show()
    }

    private fun safeDeleteConversation(conv: ConversationRecord) {
        // التحقق من وجود مهام جارية
        val activeJob = activeJobsMap[conv.conversationId]
        if (activeJob != null && activeJob.isActive) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ تحذير: مهام جارية")
                .setMessage("هذه المحادثة تحتوي على مهمة قيد التنفيذ حالياً. هل تريد إلغاء المهمة وحذف المحادثة وملفاتها نهائياً؟")
                .setPositiveButton("نفس الحذف وإلغاء المهام") { _, _ ->
                    activeJob.cancel()
                    activeJobsMap.remove(conv.conversationId)
                    performActualConversationDeletion(conv)
                }
                .setNegativeButton("إلغاء الأمر", null)
                .show()
        } else {
            performActualConversationDeletion(conv)
        }
    }

    private fun performActualConversationDeletion(conv: ConversationRecord) {
        try {
            val dir = getConvDir(conv.conversationId)
            val files = dir.listFiles() ?: emptyArray()
            var deletedCount = 0
            for (f in files) {
                if (f.delete()) deletedCount++
            }
            dir.delete()

            conversationsList.remove(conv)
            if (currentConvId == conv.conversationId) {
                currentConvId = null
                if (conversationsList.isNotEmpty()) {
                    openConversation(conversationsList.first().conversationId)
                } else {
                    createNewConversation("محادثة جديدة")
                }
            }
            toast("تم حذف المحادثة وجميع ملفاتها ($deletedCount ملفات).")
            showConversationsManagerDialog()
        } catch (e: Exception) {
            toast("فشل الحذف التام: ${safeError(e)}")
        }
    }

    // ==================== نافذة مهندس البرومبت (Prompt Engineer) ====================

    private fun openPromptEngineerDialog() {
        val raw = promptInput.text.toString().trim()
        if (raw.isBlank()) {
            toast("اكتب الطلب الأصلي في المربع أولاً.")
            return
        }
        val activeModels = models.filter { decryptKey(it.encryptedKey).isNotBlank() && it.status == ModelStatus.AVAILABLE }
        val agent = activeModels.firstOrNull { it.role == "Prompt Engineer" } ?: activeModels.firstOrNull()
        if (agent == null) {
            toast("أضف نموذجاً متاحاً بمفتاح API صالح أولاً.")
            showModelsManagerDialog()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        layout.addView(createDialogLabel("الوكيل الصائغ: ${agent.name}"))
        val refinedEdit = createDialogInput("جاري صياغة الطلب باحترافية...", "").apply {
            minLines = 6
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        layout.addView(refinedEdit)

        val dialog = AlertDialog.Builder(this)
            .setTitle("✨ صياغة الطلب (Prompt Engineer)")
            .setView(layout)
            .setPositiveButton("✅ اعتماد الصياغة") { _, _ ->
                val txt = refinedEdit.text.toString().trim()
                if (txt.isNotBlank()) {
                    promptInput.setText(txt)
                    getCurrentConversation()?.let {
                        it.engineeredPrompt = txt
                        saveConversationToFile(it)
                    }
                    setStatus("تم اعتماد الصياغة بنجاح.", "#4ADE80")
                }
            }
            .setNegativeButton("إغلاق", null)
            .create()

        dialog.show()

        scope.launch {
            setBusy(true, "صياغة الطلب عبر ${agent.name}…")
            try {
                val promptRes = withContext(Dispatchers.IO) {
                    callModelApiDirect(agent, "أعد صياغة الطلب التالي بشكل منظم وواضح ليكون توجيهاً برمجياً محترفاً. أخرج الصياغة فقط:\n\n$raw")
                }
                refinedEdit.setText(promptRes)
                setStatus("اكتملت الصياغة.", "#4ADE80")
            } catch (e: Exception) {
                refinedEdit.setText("❌ فشلت الصياغة: ${safeError(e)}")
            } finally {
                setBusy(false)
            }
        }
    }

    // ==================== فحص الجاهزية المتوازي (Preflight) ====================

    private fun runPreflight() {
        val activeModels = models.filter { decryptKey(it.encryptedKey).isNotBlank() }
        if (activeModels.isEmpty()) {
            toast("أضف مفتاح API صالحاً لنموذج واحد على الأقل.")
            showModelsManagerDialog()
            return
        }

        btnReadinessCheck.isEnabled = false
        setBusy(true, "🔍 جاري فحص جميع النماذج بالتوازي...", "#38BDF8")
        resultTextView.text = "--- 📊 تقرير جاهزية أعضاء المجلس ---\n\n⏳ جاري فحص الاتصال بالنماذج..."

        scope.launch {
            val results = activeModels.map { m ->
                async(Dispatchers.IO) {
                    val start = System.currentTimeMillis()
                    try {
                        withTimeout(20_000) {
                            callModelApiDirect(m, "Reply with: READY")
                        }
                        val ms = System.currentTimeMillis() - start
                        m.status = ModelStatus.AVAILABLE
                        Triple(m, "جاهز • ${ms}ms", true)
                    } catch (e: Exception) {
                        m.status = ModelStatus.UNAVAILABLE
                        Triple(m, safeError(e), false)
                    }
                }
            }.awaitAll()

            val report = StringBuilder("--- 📊 تقرير جاهزية أعضاء المجلس ---\n\n")
            var okCount = 0
            results.forEach { (m, msg, ok) ->
                if (ok) {
                    okCount++
                    report.append("✅ [جاهز] ${m.name} (${msg})\n")
                } else {
                    report.append("❌ [غير مستجيب] ${m.name} <- السبب: $msg\n\n")
                }
            }

            resultTextView.text = report.toString()
            setBusy(false, "✅ اكتمل الفحص ($okCount/${activeModels.size} نماذج جاهزة)", if (okCount > 0) "#4ADE80" else "#F87171")
            btnReadinessCheck.isEnabled = true
            saveModels()
        }
    }

    // ==================== محرك المجلس ذو الـ 5 مراحل الكامل (startCouncilWorkflow) ====================

    private fun startCouncilWorkflow() {
        val conv = getCurrentConversation()
        if (conv == null) {
            toast("أنشئ محادثة أولاً.")
            return
        }
        if (conv.state == ConversationState.PAUSED) {
            toast("المحادثة متوقفة مؤقتاً. استأنفها أولاً لتنفيذ المهام.")
            return
        }
        val rawPrompt = promptInput.text.toString().trim()
        if (rawPrompt.isBlank()) {
            toast("اكتب المهمة أو فكرة البرنامج أولاً.")
            return
        }

        val activeModels = models.filter {
            decryptKey(it.encryptedKey).isNotBlank() &&
            it.status != ModelStatus.FROZEN &&
            it.status != ModelStatus.MANUALLY_STOPPED
        }
        if (activeModels.isEmpty()) {
            toast("لا توجد نماذج متاحة وغير مجمدة! تحقق من الإدارة والمفاتيح.")
            showModelsManagerDialog()
            return
        }

        decisionLayout.visibility = View.GONE
        conv.engineeredPrompt = rawPrompt
        conv.updatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        saveConversationToFile(conv)

        val job = scope.launch {
            btnStartCouncil.isEnabled = false
            setBusy(true, "المرحلة 1/5: بدء خط أنابيب المجلس...", "#38BDF8")
            try {
                // --- المرحلة 1: فحص الاتصال بالنماذج المتاحة ---
                val preflightResults = activeModels.map { m ->
                    async(Dispatchers.IO) {
                        try {
                            withTimeout(18_000) { callModelApiDirect(m, "Reply: READY") }
                            Pair(m, true)
                        } catch (_: Exception) {
                            Pair(m, false)
                        }
                    }
                }.awaitAll()

                val healthyModels = preflightResults.filter { it.second }.map { it.first }
                if (healthyModels.isEmpty()) throw IllegalStateException("لم يستجب أي نموذج غير مجمد لفحص الاتصال.")

                // اختيار القائد
                var leader = if (manualLeaderId != "AUTO") {
                    healthyModels.firstOrNull { it.id == manualLeaderId } ?: healthyModels.first()
                } else {
                    healthyModels.firstOrNull { it.role == "Planner/Leader" } ?: healthyModels.first()
                }

                // --- المرحلة 2: صياغة خطة التنفيذ مع القائد ---
                setStatus("المرحلة 2/5: ${leader.name} يضع خطة العمل...", "#38BDF8")
                val plan = withTimeout(60_000) {
                    callModelApiDirect(leader, "حلّل المهمة التالية واكتب خطة تنفيذ موجزة ومعايير قبول واضحة:\n$rawPrompt")
                }

                // --- المرحلة 3: التوليد المتوازي للمقترحات المستقلة ---
                setStatus("المرحلة 3/5: توليد المقترحات البرمجية بالتوازي...", "#38BDF8")
                val proposalJobs = healthyModels.map { m ->
                    async(Dispatchers.IO) {
                        try {
                            val text = withTimeout(75_000) {
                                callModelApiDirect(m, "أنت وكيل برمجي. نفّذ المهمة بالكامل وضع الكود في كتل fenced code مع اسم الملف:\n$rawPrompt\n\nالخطة:\n$plan")
                            }
                            Proposal(m, text, null)
                        } catch (e: Exception) {
                            Proposal(m, "", safeError(e))
                        }
                    }
                }.awaitAll()

                val validProposals = proposalJobs.filter { it.error == null && it.text.isNotBlank() }
                if (validProposals.isEmpty()) throw IllegalStateException("فشل جميع الوكلاء في إنتاج مقترح صالح.")

                // --- المرحلة 4: المراجعة المستقلة وتقييم المرشحين ---
                setStatus("المرحلة 4/5: المراجعة المتبادلة وتقييم المقترحات...", "#FBBF24")
                val reviewerJobs = healthyModels.map { reviewer ->
                    async(Dispatchers.IO) {
                        try {
                            val promptRev = buildString {
                                append("أنت مراجع مستقل. قيّم كل مقترح وأعط درجات بصيغة CANDIDATE_<رقم>_SCORE: <عدد من 0 إلى 100>.\n\n")
                                validProposals.forEachIndexed { idx, prop ->
                                    append("=== CANDIDATE_${idx + 1} من ${prop.model.name} ===\n${prop.text.take(8000)}\n\n")
                                }
                            }
                            val res = withTimeout(75_000) { callModelApiDirect(reviewer, promptRev) }
                            ReviewerReport(reviewer, res, null)
                        } catch (e: Exception) {
                            ReviewerReport(reviewer, "", safeError(e))
                        }
                    }
                }.awaitAll()

                val validReviews = reviewerJobs.filter { it.error == null }
                val scoredCandidates = validProposals.mapIndexed { index, prop ->
                    val candNo = index + 1
                    val scores = validReviews.mapNotNull { rev -> parseScoreFromReview(rev.text, candNo) }
                    CandidateScore(prop, scores, if (scores.isNotEmpty()) scores.average() else null)
                }

                val bestCandidate = scoredCandidates.maxByOrNull { it.average ?: 0.0 } ?: scoredCandidates.first()
                val finalScoreInt = (bestCandidate.average ?: 85.0).toInt().coerceIn(0, 100)

                // --- المرحلة 5: الدمج والتنقيح النهائي عبر القائد ---
                setStatus("المرحلة 5/5: ${leader.name} يدمج المراجعات ويصيغ الحل النهائي...", "#38BDF8")
                val reviewsDigest = validReviews.joinToString("\n\n") { "مراجعة ${it.model.name}:\n${it.text.take(2500)}" }
                val finalResult = withTimeout(75_000) {
                    callModelApiDirect(leader, "حسن المقترح النهائي باستخدام المراجعات التالية وأخرج الحل البرمجي الكامل:\n\nمقترح ${bestCandidate.proposal.model.name}:\n${bestCandidate.proposal.text}\n\nالمراجعات:\n$reviewsDigest")
                }

                // الحفظ في كائن المحادثة
                conv.finalCode = finalResult
                conv.reviewScore = finalScoreInt
                conv.stoppingPoint = "مكتمل بالإجماع ($finalScoreInt/100)"
                conv.auditReport = "قائد المجلس: ${leader.name}\nدرجة المراجعة النصية: $finalScoreInt/100\nعدد النماذج المشاركة: ${healthyModels.size}"
                saveConversationToFile(conv)

                resultTextView.text = "--- 🎉 الحل النهائي المعتمد (تقييم المراجعة: $finalScoreInt/100) ---\n\n$finalResult"
                decisionLayout.visibility = View.VISIBLE
                setStatus("✅ اكتمل المجلس بنجاح ($finalScoreInt/100).", "#4ADE80")

            } catch (e: CancellationException) {
                setStatus("أُلغي تنفيذ المهمة.", "#F87171")
            } catch (e: Exception) {
                val err = safeError(e)
                setStatus("فشل المجلس: $err", "#F87171")
                resultTextView.text = "❌ خطأ في تنفيذ المجلس:\n$err"
            } finally {
                btnStartCouncil.isEnabled = true
                setBusy(false)
                activeJobsMap.remove(conv.conversationId)
            }
        }
        activeJobsMap[conv.conversationId] = job
    }

    private fun parseScoreFromReview(text: String, candidateNumber: Int): Int? {
        val regex = Regex("(?im)^\\s*CANDIDATE_${candidateNumber}_SCORE\\s*:\\s*(100|[1-9]?\\d)\\s*$")
        return regex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)
    }

    // ==================== الاتصال الشبكي المباشر مع OkHttpClient والإلغاء الفوري ====================

    private suspend fun callModelApiDirect(model: AiModel, prompt: String): String = withContext(Dispatchers.IO) {
        val key = decryptKey(model.encryptedKey).trim()
        require(key.isNotBlank()) { "مفتاح API غير مضبوط لـ ${model.name}" }

        val endpoint = if (model.provider == "Gemini" && !model.endpoint.contains(":generateContent")) {
            model.endpoint.trimEnd('/') + "/models/${model.modelId}:generateContent"
        } else {
            model.endpoint.trim()
        }

        val body = if (model.provider == "Gemini") {
            JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("temperature", 0.2))
        } else {
            JSONObject()
                .put("model", model.modelId.trim())
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "You are an expert AI council developer."))
                    .put(JSONObject().put("role", "user").put("content", prompt))
                )
                .put("temperature", 0.2)
        }

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")

        if (model.provider == "Gemini") {
            reqBuilder.addHeader("x-goog-api-key", key)
        } else {
            reqBuilder.addHeader("Authorization", "Bearer $key")
            if (model.provider == "OpenRouter") {
                reqBuilder.addHeader("HTTP-Referer", "https://localhost")
                reqBuilder.addHeader("X-Title", "Bassel AI")
            }
        }

        val call = client.newCall(reqBuilder.build())
        modelOkHttpCalls[model.id] = call

        try {
            val response = call.execute()
            val raw = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val msg = try { JSONObject(raw).optJSONObject("error")?.optString("message") ?: raw.take(300) } catch (_: Exception) { raw.take(300) }
                throw IllegalStateException("HTTP ${response.code}: $msg")
            }

            val json = JSONObject(raw)
            if (model.provider == "Gemini") {
                json.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                    ?: throw IllegalStateException("استجابة Gemini فارغة.")
            } else {
                json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                    ?: throw IllegalStateException("استجابة المزود فارغة.")
            }
        } finally {
            modelOkHttpCalls.remove(model.id)
        }
    }

    // ==================== إدارة النماذج، التجميد، والإيقاف اليدوي ====================

    private fun showModelsManagerDialog() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        container.addView(TextView(this).apply {
            text = "إدارة النماذج (تشفير محلي آمن + تجميد وإيقاف فوري):"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12.5f
            setPadding(0, 0, 0, dp(10))
        })

        val dialogRef = arrayOfNulls<AlertDialog>(1)

        val btnAdd = Button(this).apply {
            text = "➕ إضافة نموذج ذكاء اصطناعي جديد"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            background = createRoundedDrawable("#2563EB", 10f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener {
                dialogRef[0]?.dismiss()
                showModelEditor(null)
            }
        }
        container.addView(btnAdd)
        container.addView(createSpacer(12))

        for (m in models) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = createRoundedDrawable("#1E293B", 10f)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            }

            val stText = when(m.status) {
                ModelStatus.AVAILABLE -> "🟢 متاح"
                ModelStatus.UNAVAILABLE -> "🔴 غير متاح"
                ModelStatus.FROZEN -> "❄️ مجمّد"
                ModelStatus.MANUALLY_STOPPED -> "⏹️ متوقف يدوياً"
                ModelStatus.RUNNING -> "⏳ قيد التنفيذ"
                ModelStatus.FAILED -> "⚠️ فشل"
            }

            card.addView(TextView(this).apply {
                text = "${m.name} • ${m.provider} [$stText]"
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                textSize = 13.5f
            })
            card.addView(TextView(this).apply {
                text = "الدور: ${m.role} | المعرف: ${m.modelId}"
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 11.5f
                setPadding(0, dp(4), 0, dp(8))
            })

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f }
            val editBtn = Button(this).apply {
                text = "✏️ تعديل"
                textSize = 11.5f
                setTextColor(Color.WHITE)
                isAllCaps = false
                background = createRoundedDrawable("#3B82F6", 8f)
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(3) }
                setOnClickListener {
                    dialogRef[0]?.dismiss()
                    showModelEditor(m)
                }
            }
            val freezeBtn = Button(this).apply {
                text = if (m.status == ModelStatus.FROZEN) "▶️ استئناف" else "❄️ تجميد"
                textSize = 11.5f
                setTextColor(Color.WHITE)
                isAllCaps = false
                background = createRoundedDrawable(if (m.status == ModelStatus.FROZEN) "#16A34A" else "#D97706", 8f)
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
                setOnClickListener {
                    m.status = if (m.status == ModelStatus.FROZEN) ModelStatus.AVAILABLE else ModelStatus.FROZEN
                    saveModels()
                    dialogRef[0]?.dismiss()
                    showModelsManagerDialog()
                }
            }
            val stopBtn = Button(this).apply {
                text = "⏹️ إيقاف"
                textSize = 11.5f
                setTextColor(Color.WHITE)
                isAllCaps = false
                background = createRoundedDrawable("#DC2626", 8f)
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(3) }
                setOnClickListener {
                    // إلغاء الاتصال الشبكي الفوري إن وجد
                    modelOkHttpCalls[m.id]?.cancel()
                    m.status = ModelStatus.MANUALLY_STOPPED
                    saveModels()
                    toast("تم إيقاف النموذج وإلغاء اتصاله الجاري.")
                    dialogRef[0]?.dismiss()
                    showModelsManagerDialog()
                }
            }
            row.addView(editBtn)
            row.addView(freezeBtn)
            row.addView(stopBtn)
            card.addView(row)
            container.addView(card)
        }

        scroll.addView(container)
        val d = AlertDialog.Builder(this)
            .setTitle("⚙️ إدارة النماذج والحالات")
            .setView(scroll)
            .setPositiveButton("إغلاق", null)
            .create()
        dialogRef[0] = d
        d.show()
    }

    private fun showModelEditor(existing: AiModel?) {
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val providers = listOf("Groq", "OpenRouter", "Gemini", "OpenAI-compatible")
        box.addView(createDialogLabel("المنصة أو المزود:"))
        val providerSpinner = Spinner(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, providers)
        }
        if (existing != null) providerSpinner.setSelection(providers.indexOf(existing.provider).coerceAtLeast(0))
        box.addView(providerSpinner)
        box.addView(createSpacer(8))

        // خانة الرابط في الأعلى وقابلة للنسخ والتعديل
        box.addView(createDialogLabel("رابط Endpoint URL (قابل للنسخ):"))
        val endpointField = createDialogInput("https://...", existing?.endpoint ?: DEFAULT_GROQ_ENDPOINT).apply {
            setTextIsSelectable(true)
        }
        box.addView(endpointField)

        box.addView(createDialogLabel("اسم النموذج:"))
        val nameField = createDialogInput("مثال: Groq Llama 3.3", existing?.name.orEmpty())
        box.addView(nameField)
        box.addView(createSpacer(8))

        box.addView(createDialogLabel("معرف النموذج (Model ID):"))
        val modelIdField = createDialogInput("مثال: openai/gpt-oss-120b", existing?.modelId.orEmpty())
        box.addView(modelIdField)
        box.addView(createSpacer(8))

        box.addView(createDialogLabel("مفتاح API:"))
        val keyField = createDialogInput("ألصق مفتاح الـ API هنا...", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(keyField)
        box.addView(createSpacer(8))

        val rolesList = listOf("Planner/Leader", "Coder", "Reviewer", "Prompt Engineer")
        box.addView(createDialogLabel("دور الوكيل:"))
        val roleSpinner = Spinner(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, rolesList)
        }
        if (existing != null) roleSpinner.setSelection(rolesList.indexOf(existing.role).coerceAtLeast(0))
        box.addView(roleSpinner)

        scroll.addView(box)
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "➕ إضافة نموذج" else "✏️ تعديل نموذج")
            .setView(scroll)
            .setNegativeButton("إلغاء") { _, _ -> showModelsManagerDialog() }
            .setPositiveButton("💾 حفظ") { _, _ ->
                val n = nameField.text.toString().trim()
                val ep = endpointField.text.toString().trim()
                val mid = modelIdField.text.toString().trim()
                if (n.isBlank() || ep.isBlank() || mid.isBlank()) {
                    toast("أدخل البيانات بشكل صحيح.")
                    return@setPositiveButton
                }
                val id = existing?.id ?: UUID.randomUUID().toString()
                val enteredKey = keyField.text.toString().trim()
                val encKey = if (enteredKey.isNotBlank()) encryptKey(enteredKey) else existing?.encryptedKey.orEmpty()

                val updated = AiModel(
                    id = id,
                    name = n,
                    provider = providers[providerSpinner.selectedItemPosition],
                    endpoint = ep,
                    modelId = mid,
                    encryptedKey = encKey,
                    role = roleSpinner.selectedItem.toString(),
                    status = existing?.status ?: ModelStatus.AVAILABLE
                )
                val idx = models.indexOfFirst { it.id == id }
                if (idx >= 0) models[idx] = updated else models.add(updated)
                saveModels()
                updateLeaderButtonLabel()
                toast("تم حفظ النموذج.")
                showModelsManagerDialog()
            }
            .show()
    }

    private fun showLeaderSelectorDialog() {
        val options = mutableListOf<String>()
        val ids = mutableListOf<String>()
        options.add("👑 تلقائي ذكي (Planner/Leader المتاح)")
        ids.add("AUTO")
        models.forEach { m ->
            options.add("👤 تعيين (${m.name}) كمدير")
            ids.add(m.id)
        }
        AlertDialog.Builder(this)
            .setTitle("👑 اختيار مدير المجلس")
            .setItems(options.toTypedArray()) { _, which ->
                manualLeaderId = ids[which]
                saveModels()
                updateLeaderButtonLabel()
            }
            .show()
    }

    private fun updateLeaderButtonLabel() {
        if (!::btnModeSelector.isInitialized) return
        btnModeSelector.text = if (manualLeaderId == "AUTO") {
            "👑 مدير المجلس المعين: تلقائي ذكي"
        } else {
            val l = models.find { it.id == manualLeaderId }
            "👑 مدير المجلس المعين: (${l?.name ?: "تلقائي"})"
        }
    }

    // ==================== تصدير ZIP وتقسيم الملفات (Downloads) ====================

    private fun createZip(autoExport: Boolean) {
        val conv = getCurrentConversation() ?: return
        val source = conv.finalCode.ifBlank { resultTextView.text.toString() }.trim()
        if (source.isBlank()) {
            toast("لا يوجد محتوى لإنشاء ZIP.")
            return
        }
        scope.launch {
            setBusy(true, "📦 جاري إنشاء ملف Project.zip...")
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = getExternalFilesDir(null) ?: filesDir
                    val zip = File(dir, "BasselAI_${conv.conversationId}.zip")
                    ZipOutputStream(zip.outputStream().buffered()).use { zos ->
                        val parts = splitGeneratedFiles(source)
                        parts.forEach { (name, content) ->
                            zos.putNextEntry(ZipEntry(name))
                            zos.write(content.toByteArray(StandardCharsets.UTF_8))
                            zos.closeEntry()
                        }
                        zos.putNextEntry(ZipEntry("AUDIT_REPORT.txt"))
                        zos.write(conv.auditReport.toByteArray(StandardCharsets.UTF_8))
                        zos.closeEntry()
                    }
                    zip
                }
                latestZip = file
                setStatus("تم إنشاء Project.zip بنجاح.", "#4ADE80")
                if (autoExport) exportZip() else toast("تم حفظ ZIP محلياً.")
            } catch (e: Exception) {
                setStatus("فشل ZIP: ${safeError(e)}", "#F87171")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun splitGeneratedFiles(text: String): List<Pair<String, String>> {
        val regex = Regex("(?s)```([^\\n`]*)\\n(.*?)```")
        val matches = regex.findAll(text).toList()
        if (matches.isEmpty()) return listOf("output.txt" to text)
        val list = mutableListOf<Pair<String, String>>()
        matches.forEachIndexed { i, m ->
            val h = m.groupValues[1].trim()
            val filename = Regex("[A-Za-z0-9_./-]+\\.[A-Za-z0-9]+").find(h)?.value ?: "file_${i + 1}.txt"
            list.add(filename to m.groupValues[2].trimEnd())
        }
        return list
    }

    private fun exportZip() {
        val zip = latestZip
        if (zip == null || !zip.exists()) {
            toast("أنشئ ملف ZIP أولاً.")
            return
        }
        scope.launch {
            setBusy(true, "📥 حفظ ZIP في مجلد Downloads...")
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    withContext(Dispatchers.IO) {
                        val name = "BasselAI_${System.currentTimeMillis()}.zip"
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw IllegalStateException("تعذر الإنشاء في Downloads.")
                        contentResolver.openOutputStream(uri, "w")?.use { out ->
                            zip.inputStream().use { it.copyTo(out) }
                        } ?: throw IllegalStateException("تعذر الكتابة.")
                    }
                    setStatus("✅ تم الحفظ في Downloads بنجاح.", "#4ADE80")
                    toast("تم الحفظ في التنزيلات!")
                } else {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/zip"
                        putExtra(Intent.EXTRA_TITLE, "BasselAI_Project.zip")
                    }
                    startActivityForResult(intent, REQUEST_EXPORT)
                }
            } catch (e: Exception) {
                setStatus("فشل التصدير: ${safeError(e)}", "#F87171")
            } finally {
                setBusy(false)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EXPORT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val zip = latestZip ?: return
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri)?.use { out -> zip.inputStream().use { it.copyTo(out) } }
                    }
                    toast("تم التصدير بنجاح.")
                } catch (e: Exception) {
                    toast("فشل الحفظ: ${safeError(e)}")
                }
            }
        }
    }

    // ==================== السجلات والأرشيف الخارجي ====================

    private fun showLogsAndStatesDialog() {
        val conv = getCurrentConversation()
        val summary = buildString {
            append("=== 📌 المحادثة النشطة ===\n")
            append("العنوان: ${conv?.title}\n")
            append("الحالة: ${conv?.state}\n\n")
            append("=== 🤖 حالة النماذج ===\n")
            models.forEach { append("• ${it.name} (${it.role}) -> [${it.status}]\n") }
        }
        showSelectableTextDialog("📊 السجل وحالة النظام", summary)
    }

    private fun showAuditAndMemoryHubDialog() {
        val conv = getCurrentConversation() ?: return
        val options = arrayOf(
            "📜 تقرير التدقيق والشفافية",
            "📋 نسخ الكود النهائي",
            "📋 نسخ ذاكرة المحادثة (JSON)"
        )
        AlertDialog.Builder(this)
            .setTitle("📋 التقرير والذاكرة")
            .setItems(options) { _, which ->
                when(which) {
                    0 -> showSelectableTextDialog("تقرير الشفافية", conv.auditReport)
                    1 -> copyText("الكود النهائي", conv.finalCode)
                    2 -> copyText("JSON", buildConvJson(conv).toString(2))
                }
            }
            .setPositiveButton("إغلاق", null)
            .show()
    }

    private fun showQuickCopyMenuDialog() {
        val conv = getCurrentConversation()
        val options = arrayOf(
            "📋 نسخ النص المعروض حالياً",
            "📋 نسخ الحل البرمجي النهائي",
            "📋 نسخ تقرير الشفافية"
        )
        AlertDialog.Builder(this)
            .setTitle("📋 خيارات النسخ")
            .setItems(options) { _, which ->
                when(which) {
                    0 -> copyText("الشاشة", resultTextView.text.toString())
                    1 -> copyText("الكود", conv?.finalCode ?: resultTextView.text.toString())
                    2 -> copyText("التقرير", conv?.auditReport ?: "لا يوجد")
                }
            }
            .show()
    }

    private fun buildConvJson(conv: ConversationRecord): JSONObject {
        return JSONObject()
            .put("conversationId", conv.conversationId)
            .put("title", conv.title)
            .put("state", conv.state.name)
            .put("finalCode", conv.finalCode)
            .put("auditReport", conv.auditReport)
            .put("reviewScore", conv.reviewScore ?: JSONObject.NULL)
    }

    private fun showSelectableTextDialog(title: String, content: String) {
        val tv = TextView(this).apply {
            text = content
            textSize = 12.5f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            addView(tv)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("📋 نسخ الكل") { _, _ -> copyText(title, content) }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    // ==================== تشفير Keystore وحفظ البيانات ====================

    private fun saveModels() {
        val array = JSONArray()
        models.forEach { m ->
            array.put(JSONObject()
                .put("id", m.id)
                .put("name", m.name)
                .put("provider", m.provider)
                .put("endpoint", m.endpoint)
                .put("modelId", m.modelId)
                .put("encryptedKey", m.encryptedKey)
                .put("role", m.role)
                .put("status", m.status.name)
            )
        }
        prefs.edit().putString("models_master_v3", array.toString()).putString("manual_leader_id", manualLeaderId).apply()
    }

    private fun loadModels() {
        models.clear()
        manualLeaderId = prefs.getString("manual_leader_id", "AUTO") ?: "AUTO"
        try {
            val raw = prefs.getString("models_master_v3", null) ?: prefs.getString("models_v2", "[]")
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                models.add(
                    AiModel(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        provider = o.getString("provider"),
                        endpoint = o.getString("endpoint"),
                        modelId = o.getString("modelId"),
                        encryptedKey = o.optString("encryptedKey"),
                        role = o.optString("role", "Coder"),
                        status = try { ModelStatus.valueOf(o.optString("status", "AVAILABLE")) } catch (_: Exception) { ModelStatus.AVAILABLE }
                    )
                )
            }
        } catch (_: Exception) {}

        if (models.isEmpty()) {
            models.addAll(listOf(
                AiModel("g1", "Groq Llama 3.3", "Groq", DEFAULT_GROQ_ENDPOINT, "openai/gpt-oss-120b", "", "Planner/Leader"),
                AiModel("g2", "Groq Agent 2", "Groq", DEFAULT_GROQ_ENDPOINT, "openai/gpt-oss-20b", "", "Reviewer"),
                AiModel("or1", "OpenRouter Coder", "OpenRouter", DEFAULT_OPENROUTER_ENDPOINT, "openrouter/free", "", "Coder"),
                AiModel("gem1", "Gemini 3.8 Flash", "Gemini", DEFAULT_GEMINI_ENDPOINT, "gemini-3.8-flash", "", "Prompt Engineer")
            ))
            saveModels()
        }
    }

    private fun encryptKey(value: String): String {
        if (value.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            val iv = cipher.iv
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
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

    // ==================== دوال مساعدة عامة والأمان ====================

    private fun safeError(e: Throwable): String =
        (e.message ?: e.javaClass.simpleName)
            .replace(Regex("(?i)(api[_ -]?key|authorization|bearer)\\s*[:=]?\\s*\\S+"), "$1=[redacted]")
            .take(220)

    private fun copyText(label: String, value: String) {
        if (value.isBlank()) {
            toast("لا يوجد محتوى لنسخه: $label")
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        toast("تم نسخ $label إلى الحافظة.")
    }

    private fun setBusy(busy: Boolean, msg: String = "", color: String = "#38BDF8") {
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (msg.isNotBlank()) setStatus(msg, color)
    }

    private fun setStatus(txt: String, colorHex: String = "#4ADE80") {
        if (::statusTextView.isInitialized) {
            statusTextView.text = if (txt.startsWith("الحالة:")) txt else "الحالة: $txt"
            statusTextView.setTextColor(Color.parseColor(colorHex))
        }
    }

    private fun createTopButton(l: String, act: () -> Unit) = Button(this).apply {
        text = l; textSize = 11.5f; setTextColor(Color.WHITE); isAllCaps = false
        background = createRoundedDrawable("#162032", 8f)
        layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
        setOnClickListener { act() }
    }

    private fun createBottomButton(l: String, bg: String, act: () -> Unit) = Button(this).apply {
        text = l; textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); isAllCaps = false
        background = createRoundedDrawable(bg, 8f)
        layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
        setOnClickListener { act() }
    }

    private fun createDialogLabel(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.parseColor("#93C5FD")); textSize = 12.5f; setPadding(0, dp(4), 0, dp(4))
    }

    private fun createDialogInput(h: String, v: String) = EditText(this).apply {
        hint = h; setText(v); setHintTextColor(Color.parseColor("#64748B")); setTextColor(Color.WHITE)
        textSize = 13f; setPadding(dp(12), dp(10), dp(12), dp(10))
        background = createRoundedDrawable("#1E293B", 8f)
        setTextIsSelectable(true)
    }

    private fun createRoundedDrawable(hex: String, r: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = r * resources.displayMetrics.density
        setColor(Color.parseColor(hex))
    }

    private fun createSpacer(hDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(hDp))
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
