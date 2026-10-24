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
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : Activity() {

// ==================== الحالات والتعدادات ====================  

enum class ModelStatus { AVAILABLE, UNAVAILABLE, FROZEN, MANUALLY_STOPPED, RUNNING, FAILED }  
enum class ConversationState { ACTIVE, PAUSED, DELETING }  
enum class ApiFormat { GEMINI_GENERATE_CONTENT, OPENAI_CHAT_COMPLETIONS }  
enum class AuthType { GOOGLE_API_KEY_HEADER, BEARER_HEADER, X_API_KEY_HEADER }  

sealed class KeyDecryptResult {  
    object Empty : KeyDecryptResult()  
    data class Success(val plainKey: String) : KeyDecryptResult()  
    data class CorruptedData(val reason: String) : KeyDecryptResult()  
    data class KeystoreError(val reason: String) : KeyDecryptResult()  
}  

class KeyCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)  
class PausedWorkflowException(val pausedAtStage: String) : Exception("توقف مؤقت عند: $pausedAtStage")  

// ==================== هياكل البيانات والقوالب ====================  

data class ModelTemplate(  
    val templateName: String,  
    val provider: String,  
    val defaultDisplayName: String,  
    val modelId: String,  
    val defaultEndpoint: String,  
    val apiFormat: ApiFormat,  
    val authType: AuthType,  
    val suggestedRole: String  
)  

data class AiModel(  
    val id: String,  
    var name: String,  
    var provider: String,  
    var endpoint: String,  
    var modelId: String,  
    var encryptedKey: String,  
    var role: String,  
    var status: ModelStatus = ModelStatus.AVAILABLE,  
    var apiFormat: ApiFormat = ApiFormat.OPENAI_CHAT_COMPLETIONS,  
    var authType: AuthType = AuthType.BEARER_HEADER,  
    var lastCheckOk: Boolean? = null,  
    var lastCheckMessage: String = "لم يُفحص بعد"  
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
    var consensusAchieved: Boolean = false,  
    var lastCompletedStage: Int = 0,  
    var cachedPlan: String = "",  
    var cachedProposalsJson: JSONArray = JSONArray(),  
    var cachedReviewsJson: JSONArray = JSONArray(),  
    val messagesHistory: JSONArray = JSONArray()  
)  

private data class Proposal(val modelId: String, val modelName: String, val text: String, val error: String?)  
private data class ReviewerReport(val reviewerId: String, val reviewerName: String, val text: String, val error: String?)  

data class EvaluationDecision(  
    val selectedProposal: Proposal,  
    val averageScore: Int?,  
    val validReviewCount: Int,  
    val requiredQuorum: Int,  
    val consensusReached: Boolean,  
    val decisionLabel: String,  
    val detailedAuditLog: String  
)  

private data class TrackedHttpCall(  
    val requestId: String,  
    val conversationId: String?,  
    val modelId: String,  
    val call: okhttp3.Call  
)  

// ==================== المتغيرات العامة ونطاق العمل ====================  

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
private val activeHttpCalls = ConcurrentHashMap<String, TrackedHttpCall>()  
private val deletedConversationIds = ConcurrentHashMap.newKeySet<String>()  

private val conversationsList = mutableListOf<ConversationRecord>()  
private var currentConvId: String? = null  
private var manualLeaderId: String = "AUTO"  
private var consensusThreshold: Int = 90  
private var latestZip: File? = null  

private lateinit var btnModeSelector: Button  
private lateinit var activeTaskBanner: TextView  
private lateinit var btnPauseResume: Button  
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

    val ROLES_LIST = listOf("Planner/Leader", "Coder", "Reviewer", "Prompt Engineer")  
    val PROVIDERS_LIST = listOf("Groq", "Gemini", "OpenRouter", "OpenAI-compatible")  

    val DEFAULT_TEMPLATES = listOf(  
        ModelTemplate(  
            templateName = "Groq — Llama 3.3 70B Versatile",  
            provider = "Groq",  
            defaultDisplayName = "Groq Llama 3.3",  
            modelId = "llama-3.3-70b-versatile",  
            defaultEndpoint = DEFAULT_GROQ_ENDPOINT,  
            apiFormat = ApiFormat.OPENAI_CHAT_COMPLETIONS,  
            authType = AuthType.BEARER_HEADER,  
            suggestedRole = "Planner/Leader"  
        ),  
        ModelTemplate(  
            templateName = "Groq — GPT-OSS 120B",  
            provider = "Groq",  
            defaultDisplayName = "Groq GPT-OSS 120B",  
            modelId = "openai/gpt-oss-120b",  
            defaultEndpoint = DEFAULT_GROQ_ENDPOINT,  
            apiFormat = ApiFormat.OPENAI_CHAT_COMPLETIONS,  
            authType = AuthType.BEARER_HEADER,  
            suggestedRole = "Coder"  
        ),  
        ModelTemplate(  
            templateName = "Groq — GPT-OSS 20B (سريع للمراجعة)",  
            provider = "Groq",  
            defaultDisplayName = "Groq GPT-OSS 20B",  
            modelId = "openai/gpt-oss-20b",  
            defaultEndpoint = DEFAULT_GROQ_ENDPOINT,  
            apiFormat = ApiFormat.OPENAI_CHAT_COMPLETIONS,  
            authType = AuthType.BEARER_HEADER,  
            suggestedRole = "Reviewer"  
        ),  
        ModelTemplate(  
            templateName = "Google Gemini — Gemini 2.5 Flash",  
            provider = "Gemini",  
            defaultDisplayName = "Gemini 2.5 Flash",  
            modelId = "gemini-2.5-flash",  
            defaultEndpoint = DEFAULT_GEMINI_ENDPOINT,  
            apiFormat = ApiFormat.GEMINI_GENERATE_CONTENT,  
            authType = AuthType.GOOGLE_API_KEY_HEADER,  
            suggestedRole = "Prompt Engineer"  
        ),  
        ModelTemplate(  
            templateName = "OpenRouter — DeepSeek Chat ( أو معرّف مخصص)",  
            provider = "OpenRouter",  
            defaultDisplayName = "OpenRouter DeepSeek",  
            modelId = "deepseek/deepseek-chat",  
            defaultEndpoint = DEFAULT_OPENROUTER_ENDPOINT,  
            apiFormat = ApiFormat.OPENAI_CHAT_COMPLETIONS,  
            authType = AuthType.BEARER_HEADER,  
            suggestedRole = "Coder"  
        ),  
        ModelTemplate(  
            templateName = "مخصص — OpenAI-Compatible Endpoint",  
            provider = "OpenAI-compatible",  
            defaultDisplayName = "Custom OpenAI Model",  
            modelId = "gpt-4o-mini",  
            defaultEndpoint = DEFAULT_OPENAI_ENDPOINT,  
            apiFormat = ApiFormat.OPENAI_CHAT_COMPLETIONS,  
            authType = AuthType.BEARER_HEADER,  
            suggestedRole = "Coder"  
        )  
    )  
}  

override fun onCreate(savedInstanceState: Bundle?) {  
    super.onCreate(savedInstanceState)  
    window.statusBarColor = Color.parseColor("#090D16")  
    window.navigationBarColor = Color.parseColor("#090D16")  

    rootRepoDir = File(filesDir, "conversations_repository")  
    if (!rootRepoDir.exists()) rootRepoDir.mkdirs()  

    buildMasterUi()  
    loadModels()  
    val corruptedCount = loadAllConversations()  

    if (conversationsList.isNotEmpty()) {  
        openConversation(conversationsList.first().conversationId)  
    } else if (corruptedCount == 0) {  
        createNewConversation("محادثة رئيسية")  
    } else {  
        setStatus("⚠️ تعذر قراءة $corruptedCount ملف محادثة تالف؛ تم الاحتفاظ بنسخها دون مسح.", "#F87171")  
        createNewConversation("محادثة جديدة (آمنة)")  
    }  
    updateLeaderButtonLabel()  
}  

override fun onDestroy() {  
    conversationJobs.values.forEach { it.cancel() }  
    activeHttpCalls.values.forEach { it.call.cancel() }  
    activeHttpCalls.clear()  
    scope.cancel()  
    super.onDestroy()  
}  

// ==================== بناء الواجهة العربية RTL ====================  

private fun buildMasterUi() {  
    window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL  

    val rootLayout = LinearLayout(this).apply {  
        orientation = LinearLayout.VERTICAL  
        setBackgroundColor(Color.parseColor("#090D16"))  
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)  
        setPadding(dp(14), dp(14), dp(14), dp(10))  
    }  

    val titleText = TextView(this).apply {  
        text = "مجلس الإدارة والوكلاء المتزامن - Bassel AI (4argon)"  
        textSize = 15.5f  
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
    topBar.addView(createTopButton("📊 السجل والحالات") { showLogsAndStatesDialog() })  
    topBar.addView(createTopButton("⚙️ المفاتيح والنماذج") { showModelsManagerDialog() })  
    rootLayout.addView(topBar)  

    rootLayout.addView(createSpacer(8))  

    btnModeSelector = Button(this).apply {  
        textSize = 12.5f  
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
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)  
    }  
    activeTaskBanner = TextView(this).apply {  
        text = "📂 المحادثة الحالية: [جديد]"  
        textSize = 11f  
        setTextColor(Color.parseColor("#A7F3D0"))  
        background = createRoundedDrawable("#064E3B", 8f)  
        setPadding(dp(10), dp(6), dp(10), dp(6))  
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2.1f)  
    }  
    btnPauseResume = Button(this).apply {  
        text = "⏸️ إيقاف مؤقت"  
        textSize = 10.5f  
        setTextColor(Color.WHITE)  
        isAllCaps = false  
        background = createRoundedDrawable("#1E3A8A", 8f)  
        layoutParams = LinearLayout.LayoutParams(0, dp(38), 0.9f).apply { marginStart = dp(4) }  
        setOnClickListener { togglePauseResumeCurrentConversation() }  
    }  
    bannerRow.addView(activeTaskBanner)  
    bannerRow.addView(btnPauseResume)  
    rootLayout.addView(bannerRow)  

    statusTextView = TextView(this).apply {  
        text = "الحالة: لم يتم فحص جاهزية النماذج بعد — اضغط [فحص جاهزية الكل] أو ابدأ المجلس."  
        textSize = 12f  
        setTextColor(Color.parseColor("#38BDF8"))  
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
        text = "--- 📊 نظام Bassel AI (إصدار 4argon المراجع) ---\n\n" +  
                "• يرجى إضافة مفاتيح API من [⚙️ المفاتيح والنماذج] ثم إجراء [🔍 فحص جاهزية الكل]."  
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

// ==================== تخزين المحادثات وسلامة الملفات ====================  

private fun isValidConversationId(id: String): Boolean {  
    return id.matches(Regex("^[a-zA-Z0-9_\\-]{3,64}$")) && !id.contains("..")  
}  

private fun getSafeConversationDir(convId: String, createIfMissing: Boolean): File? {  
    if (!isValidConversationId(convId)) return null  
    return try {  
        val rootCanonical = rootRepoDir.canonicalFile  
        val target = File(rootCanonical, convId).canonicalFile  
        if (!target.path.startsWith(rootCanonical.path + File.separator)) {  
            null  
        } else {  
            if (createIfMissing && !target.exists()) target.mkdirs()  
            target  
        }  
    } catch (_: Exception) {  
        null  
    }  
}  

private fun loadAllConversations(): Int {  
    conversationsList.clear()  
    var corruptedFilesCount = 0  
    val dirs = rootRepoDir.listFiles { f -> f.isDirectory } ?: emptyArray()  
    for (d in dirs) {  
        if (!isValidConversationId(d.name)) continue  
        val primaryFile = File(d, "conv_meta.json")  
        val backupFile = File(d, "conv_meta.json.bak")  
        if (!primaryFile.exists() && !backupFile.exists()) continue  

        val loadedRecord = readConversationJsonSafe(primaryFile) ?: readConversationJsonSafe(backupFile)  
        if (loadedRecord != null) {  
            if (loadedRecord.state == ConversationState.DELETING) {  
                continue  
            }  
            conversationsList.add(loadedRecord)  
        } else {  
            corruptedFilesCount++  
            try {  
                if (primaryFile.exists()) {  
                    val preserved = File(d, "conv_meta.corrupted_${System.currentTimeMillis()}.json")  
                    primaryFile.renameTo(preserved)  
                }  
            } catch (_: Exception) {}  
        }  
    }  
    conversationsList.sortByDescending { it.updatedAt }  
    return corruptedFilesCount  
}  

private fun readConversationJsonSafe(file: File): ConversationRecord? {  
    if (!file.exists() || file.length() <= 0L) return null  
    return try {  
        val o = JSONObject(file.readText(StandardCharsets.UTF_8))  
        val convId = o.getString("conversationId")  
        if (!isValidConversationId(convId)) return null  
        ConversationRecord(  
            conversationId = convId,  
            title = o.optString("title", "محادثة").ifBlank { "محادثة" },  
            createdAt = o.optString("createdAt", ""),  
            updatedAt = o.optString("updatedAt", ""),  
            state = try { ConversationState.valueOf(o.optString("state", "ACTIVE")) } catch (_: Exception) { ConversationState.ACTIVE },  
            stoppingPoint = o.optString("stoppingPoint", "جاهز"),  
            engineeredPrompt = o.optString("engineeredPrompt", ""),  
            finalCode = o.optString("finalCode", ""),  
            auditReport = o.optString("auditReport", ""),  
            reviewScore = if (!o.isNull("reviewScore")) o.optInt("reviewScore") else null,  
            consensusAchieved = o.optBoolean("consensusAchieved", false),  
            lastCompletedStage = o.optInt("lastCompletedStage", 0),  
            cachedPlan = o.optString("cachedPlan", ""),  
            cachedProposalsJson = o.optJSONArray("cachedProposalsJson") ?: JSONArray(),  
            cachedReviewsJson = o.optJSONArray("cachedReviewsJson") ?: JSONArray(),  
            messagesHistory = o.optJSONArray("messagesHistory") ?: JSONArray()  
        )  
    } catch (_: Exception) {  
        null  
    }  
}  

private fun saveConversationToFile(conv: ConversationRecord): Boolean {  
    if (conv.state == ConversationState.DELETING || deletedConversationIds.contains(conv.conversationId)) {  
        return false  
    }  
    val dir = getSafeConversationDir(conv.conversationId, createIfMissing = true) ?: return false  
    val targetFile = File(dir, "conv_meta.json")  
    val tempFile = File(dir, "conv_meta.json.tmp")  
    val backupFile = File(dir, "conv_meta.json.bak")  

    return try {  
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
            put("consensusAchieved", conv.consensusAchieved)  
            put("lastCompletedStage", conv.lastCompletedStage)  
            put("cachedPlan", conv.cachedPlan)  
            put("cachedProposalsJson", conv.cachedProposalsJson)  
            put("cachedReviewsJson", conv.cachedReviewsJson)  
            put("messagesHistory", conv.messagesHistory)  
        }  
        val bytes = obj.toString(2).toByteArray(StandardCharsets.UTF_8)  
        FileOutputStream(tempFile).use { fos ->  
            fos.write(bytes)  
            fos.flush()  
            fos.fd.sync()  
        }  
        if (!tempFile.exists() || tempFile.length() <= 0L) {  
            throw IOException("فشل التحقق من الملف المؤقت بعد الكتابة.")  
        }  
        if (targetFile.exists() && targetFile.length() > 0L) {  
            targetFile.copyTo(backupFile, overwrite = true)  
        }  
        if (!tempFile.renameTo(targetFile)) {  
            tempFile.copyTo(targetFile, overwrite = true)  
            tempFile.delete()  
        }  
        true  
    } catch (e: Exception) {  
        try { if (tempFile.exists()) tempFile.delete() } catch (_: Exception) {}  
        runOnUiThread {  
            toast("تعذر حفظ بيانات المحادثة: ${safeError(e)}")  
        }  
        false  
    }  
}  

private fun getCurrentConversation(): ConversationRecord? {  
    val id = currentConvId ?: return null  
    if (deletedConversationIds.contains(id)) return null  
    return conversationsList.find { it.conversationId == id && it.state != ConversationState.DELETING }  
}  

private fun createNewConversation(title: String): ConversationRecord {  
    val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())  
    val id = "conv_${System.currentTimeMillis()}_${(100..999).random()}"  
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
        reviewScore = null  
    )  
    conversationsList.add(0, record)  
    saveConversationToFile(record)  
    openConversation(id)  
    return record  
}  

private fun openConversation(convId: String) {  
    val conv = conversationsList.find { it.conversationId == convId && it.state != ConversationState.DELETING } ?: return  
    currentConvId = conv.conversationId  
    promptInput.setText(conv.engineeredPrompt)  
    updateConversationBannerUI(conv)  

    val isRunning = conversationJobs[conv.conversationId]?.isActive == true  
    btnStartCouncil.isEnabled = !isRunning  
    progressBar.visibility = if (isRunning) View.VISIBLE else View.GONE  

    if (conv.finalCode.isNotBlank()) {  
        val scoreInfo = conv.reviewScore?.let { "$it/100" } ?: "غير متاح"  
        val statusHeader = if (conv.consensusAchieved) {  
            "--- 🎉 الحل النهائي (تحقق معيار الإجماع: $scoreInfo) ---"  
        } else {  
            "--- 📌 النتيجة النهائية (الأعلى تقييماً ضمن المراجعات المتاحة: $scoreInfo) ---"  
        }  
        resultTextView.text = "$statusHeader\n\n${conv.finalCode}"  
        decisionLayout.visibility = View.VISIBLE  
    } else {  
        resultTextView.text = "--- 📂 المحادثة النشطة: ${conv.title} ---\n" +  
                "• الحالة: ${conv.state.name}\n" +  
                "• آخر نقطة توقف: ${conv.stoppingPoint}\n" +  
                "• آخر مرحلة مكتملة: ${conv.lastCompletedStage}/5\n\n" +  
                "اكتب فكرتك بالأعلى ثم اضغط [🚀 بدء المجلس]."  
        decisionLayout.visibility = View.GONE  
    }  
    setStatus(  
        "المحادثة المفتوحة: ${conv.title} (${conv.stoppingPoint})",  
        if (conv.state == ConversationState.ACTIVE) "#4ADE80" else "#FBBF24"  
    )  
}  

private fun updateConversationBannerUI(conv: ConversationRecord) {  
    activeTaskBanner.text = "📂 المحادثة: [${conv.title}] (${conv.state.name})"  
    activeTaskBanner.background = createRoundedDrawable(  
        if (conv.state == ConversationState.PAUSED) "#78350F" else "#064E3B",  
        8f  
    )  
    btnPauseResume.text = if (conv.state == ConversationState.PAUSED) "▶️ استئناف" else "⏸️ إيقاف مؤقت"  
}  

private fun togglePauseResumeCurrentConversation() {  
    val conv = getCurrentConversation() ?: return  
    val runningJob = conversationJobs[conv.conversationId]?.isActive == true  

    if (conv.state == ConversationState.ACTIVE) {  
        conv.state = ConversationState.PAUSED  
        conv.stoppingPoint = "أُوقفت مؤقتاً عند المرحلة ${conv.lastCompletedStage}/5"  
        saveConversationToFile(conv)  
        updateConversationBannerUI(conv)  
        if (runningJob) {  
            setStatus(  
                "⏸️ تم تفعيل الإيقاف المؤقت: لن تبدأ أي مرحلة جديدة، وسيتم التوقف فور انتهاء الطلب الشبكي الجاري.",  
                "#FBBF24"  
            )  
        } else {  
            setStatus("⏸️ المحادثة في وضع الإيقاف المؤقت. لن تبدأ مهام جديدة حتى الاستئناف.", "#FBBF24")  
        }  
    } else if (conv.state == ConversationState.PAUSED) {  
        conv.state = ConversationState.ACTIVE  
        conv.stoppingPoint = if (conv.lastCompletedStage in 1..4) {  
            "جاهز لاستئناف العمل من بعد المرحلة ${conv.lastCompletedStage}/5"  
        } else {  
            "نشطة وجاهزة"  
        }  
        saveConversationToFile(conv)  
        updateConversationBannerUI(conv)  
        setStatus("▶️ تم استئناف المحادثة. يمكنك المتابعة من المرحلة ${conv.lastCompletedStage + 1}/5.", "#4ADE80")  
    }  
}  

private fun showConversationsManagerDialog() {  
    if (conversationsList.isEmpty()) createNewConversation("محادثة جديدة")  
    val items = Array(conversationsList.size) { i ->  
        val c = conversationsList[i]  
        val runningTag = if (conversationJobs[c.conversationId]?.isActive == true) "⏳ [قيد التنفيذ] " else ""  
        val activeTag = if (c.conversationId == currentConvId) "🟢 [مفتوحة] " else ""  
        "$activeTag$runningTag🗂️ ${c.title} (${c.state.name})\n📌 ${c.stoppingPoint}"  
    }  
    AlertDialog.Builder(this)  
        .setTitle("📁 إدارة المحادثات (${conversationsList.size})")  
        .setItems(items) { _, which ->  
            val chosen = conversationsList.getOrNull(which) ?: return@setItems  
            showConversationActionsDialog(chosen)  
        }  
        .setPositiveButton("➕ محادثة جديدة") { _, _ ->  
            createNewConversation("محادثة ${conversationsList.size + 1}")  
        }  
        .setNegativeButton("إغلاق", null)  
        .show()  
}  

private fun showConversationActionsDialog(conv: ConversationRecord) {  
    val options = arrayOf(  
        "📂 فتح وعرض هذه المحادثة",  
        "✏️ إعادة تسمية المحادثة",  
        "🔄 إعادة تعيين مراحل التوقف (البدء من مرحلة 1)",  
        "🗑️ حذف المحادثة وجميع ملفاتها نهائياً"  
    )  
    AlertDialog.Builder(this)  
        .setTitle("🗂️ ${conv.title}")  
        .setItems(options) { _, which ->  
            when (which) {  
                0 -> openConversation(conv.conversationId)  
                1 -> {  
                    val input = createDialogInput("عنوان المحادثة الجديد", conv.title)  
                    AlertDialog.Builder(this)  
                        .setTitle("✏️ إعادة تسمية المحادثة")  
                        .setView(input)  
                        .setPositiveButton("حفظ") { _, _ ->  
                            val newTitle = input.text.toString().trim()  
                            if (newTitle.isNotBlank()) {  
                                conv.title = newTitle  
                                saveConversationToFile(conv)  
                                if (currentConvId == conv.conversationId) updateConversationBannerUI(conv)  
                            }  
                        }  
                        .setNegativeButton("إلغاء", null)  
                        .show()  
                }  
                2 -> {  
                    conv.lastCompletedStage = 0  
                    conv.cachedPlan = ""  
                    conv.cachedProposalsJson = JSONArray()  
                    conv.cachedReviewsJson = JSONArray()  
                    conv.stoppingPoint = "تم تصفير المراحل للبدء من جديد"  
                    saveConversationToFile(conv)  
                    if (currentConvId == conv.conversationId) openConversation(conv.conversationId)  
                    toast("تمت إعادة تعيين المراحل.")  
                }  
                3 -> safeDeleteConversation(conv)  
            }  
        }  
        .show()  
}  

private fun safeDeleteConversation(conv: ConversationRecord) {  
    val activeJob = conversationJobs[conv.conversationId]  
    val isRunning = activeJob != null && activeJob.isActive  
    val msg = if (isRunning) {  
        "⚠️ هذه المحادثة تحتوي على مهام وطلبات شبكة جارية حالياً.\nسيتم إلغاء جميع طلبات الشبكة والمهام المرتبطة بها فوراً ثم مسح مجلدها وملفاتها."  
    } else {  
        "هل تريد بالتأكيد حذف المحادثة (${conv.title}) وجميع ملفاتها وتقاريرها نهائياً؟"  
    }  

    AlertDialog.Builder(this)  
        .setTitle("🗑️ تأكيد حذف المحادثة")  
        .setMessage(msg)  
        .setPositiveButton("تأكيد الحذف النهائي") { _, _ ->  
            executeSafeConversationDeletion(conv)  
        }  
        .setNegativeButton("تراجع", null)  
        .show()  
}  

private fun executeSafeConversationDeletion(conv: ConversationRecord) {  
    conv.state = ConversationState.DELETING  
    deletedConversationIds.add(conv.conversationId)  

    cancelCallsForConversation(conv.conversationId)  
    val runningJob = conversationJobs.remove(conv.conversationId)  
    runningJob?.cancel(CancellationException("حُذفت المحادثة بواسطة المستخدم."))  

    scope.launch {  
        withContext(Dispatchers.IO) {  
            runningJob?.join()  
        }  
        val dir = getSafeConversationDir(conv.conversationId, createIfMissing = false)  
        var failedDeletes = 0  
        if (dir != null && dir.exists()) {  
            failedDeletes = deleteDirectoryRecursivelySafe(dir)  
        }  
        val extDir = getExternalFilesDir(null) ?: filesDir  
        val convZip = File(extDir, "BasselAI_${conv.conversationId}.zip")  
        if (convZip.exists() && !convZip.delete()) {  
            failedDeletes++  
        }  

        conversationsList.removeAll { it.conversationId == conv.conversationId }  
        if (currentConvId == conv.conversationId) {  
            currentConvId = null  
            if (conversationsList.isNotEmpty()) {  
                openConversation(conversationsList.first().conversationId)  
            } else {  
                createNewConversation("محادثة رئيسية")  
            }  
        }  

        if (failedDeletes == 0) {  
            toast("تم حذف المحادثة وجميع ملفاتها بنجاح.")  
        } else {  
            AlertDialog.Builder(this@MainActivity)  
                .setTitle("⚠️ حذف غير مكتمل جزئياً")  
                .setMessage("تم إزالة المحادثة وإلغاء مهامها، ولكن تعذر حذف $failedDeletes ملف من وحدة التخزين.")  
                .setPositiveButton("حسناً", null)  
                .show()  
        }  
    }  
}  

private fun deleteDirectoryRecursivelySafe(target: File): Int {  
    var failures = 0  
    try {  
        val rootCanonical = rootRepoDir.canonicalFile  
        val currentCanonical = target.canonicalFile  
        if (!currentCanonical.path.startsWith(rootCanonical.path + File.separator)) {  
            return 1  
        }  
        if (currentCanonical.isDirectory) {  
            currentCanonical.listFiles()?.forEach { child ->  
                failures += deleteDirectoryRecursivelySafe(child)  
            }  
        }  
        if (currentCanonical.exists() && !currentCanonical.delete()) {  
            failures++  
        }  
    } catch (_: Exception) {  
        failures++  
    }  
    return failures  
}
    // ==================== إدارة الطلبات الشبكية وإلغائها ====================

    private fun cancelCallsForConversation(convId: String) {
        activeHttpCalls.entries.removeIf { (_, tracked) ->
            if (tracked.conversationId == convId) {
                try { tracked.call.cancel() } catch (_: Exception) {}
                true
            } else {
                false
            }
        }
    }

    private fun cancelCallsForModel(modelId: String): Int {
        var cancelled = 0
        activeHttpCalls.entries.removeIf { (_, tracked) ->
            if (tracked.modelId == modelId) {
                try { tracked.call.cancel() } catch (_: Exception) {}
                cancelled++
                true
            } else {
                false
            }
        }
        return cancelled
    }

    private fun isModelEligible(m: AiModel): Boolean {
        if (m.status == ModelStatus.FROZEN || m.status == ModelStatus.MANUALLY_STOPPED) return false
        return decryptKeyDetailed(m.encryptedKey) is KeyDecryptResult.Success
    }

    // ==================== مهندس البرومبت وفحص الجاهزية ====================

    private fun openPromptEngineerDialog() {
        val raw = promptInput.text.toString().trim()
        if (raw.isBlank()) {
            toast("اكتب الطلب الأصلي في المربع أولاً.")
            return
        }
        val eligible = models.filter { isModelEligible(it) }
        val agent = eligible.firstOrNull { it.role == "Prompt Engineer" } ?: eligible.firstOrNull()
        if (agent == null) {
            toast("لا يوجد نموذج متاح بمفتاح صالح وغير مجمّد.")
            showModelsManagerDialog()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        layout.addView(createDialogLabel("الوكيل الصائغ: ${agent.name} (${agent.modelId})"))

        val refinedEdit = createDialogInput("جاري صياغة الطلب...", "").apply {
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
                if (txt.isNotBlank() && !txt.startsWith("❌")) {
                    promptInput.setText(txt)
                    getCurrentConversation()?.let {
                        it.engineeredPrompt = txt
                        saveConversationToFile(it)
                    }
                    setStatus("تم اعتماد الصياغة الهندسية للطلب.", "#4ADE80")
                }
            }
            .setNegativeButton("إغلاق", null)
            .create()
        dialog.show()

        val targetConvId = currentConvId
        scope.launch {
            setBusy(true, "⏳ جاري صياغة الطلب عبر ${agent.name}...")
            try {
                val res = withTimeout(60_000) {
                    callModelApiDirect(
                        agent,
                        "أعد صياغة الطلب التالي بوضوح وتنظيم برمجي احترافي دون إضافة متطلبات مخترعة. أخرج الصياغة فقط:\n\n$raw",
                        targetConvId
                    )
                }
                refinedEdit.setText(res)
                setStatus("اكتملت صياغة الطلب.", "#4ADE80")
            } catch (e: CancellationException) {
                refinedEdit.setText("❌ أُلغي طلب الصياغة.")
                setStatus("أُلغي طلب الصياغة.", "#FBBF24")
            } catch (e: Exception) {
                refinedEdit.setText("❌ فشلت الصياغة: ${safeError(e)}")
                setStatus("فشلت الصياغة: ${safeError(e)}", "#F87171")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun runPreflight() {
        if (models.isEmpty()) {
            toast("قائمة النماذج فارغة.")
            return
        }
        btnReadinessCheck.isEnabled = false
        setBusy(true, "🔍 جاري فحص جاهزية النماذج المؤهلة بالتوازي...", "#38BDF8")

        scope.launch {
            val reportLines = mutableListOf<String>()
            var readyCount = 0
            var testedCount = 0

            val deferredChecks = models.map { m ->
                async(Dispatchers.IO) {
                    if (m.status == ModelStatus.FROZEN || m.status == ModelStatus.MANUALLY_STOPPED) {
                        return@async Triple(m, "⏸️ مستبعد (${m.status.name}) — لم يُفحص حفاظاً على حالته", false)
                    }
                    when (val keyRes = decryptKeyDetailed(m.encryptedKey)) {
                        is KeyDecryptResult.Empty -> {
                            m.status = ModelStatus.UNAVAILABLE
                            m.lastCheckOk = false
                            m.lastCheckMessage = "بدون مفتاح API"
                            return@async Triple(m, "🔴 بدون مفتاح API محفوظ", false)
                        }
                        is KeyDecryptResult.CorruptedData -> {
                            m.status = ModelStatus.FAILED
                            m.lastCheckOk = false
                            m.lastCheckMessage = keyRes.reason
                            return@async Triple(m, "⚠️ مفتاح تالف: ${keyRes.reason}", false)
                        }
                        is KeyDecryptResult.KeystoreError -> {
                            m.status = ModelStatus.FAILED
                            m.lastCheckOk = false
                            m.lastCheckMessage = keyRes.reason
                            return@async Triple(m, "⚠️ خطأ Keystore: ${keyRes.reason}", false)
                        }
                        is KeyDecryptResult.Success -> {
                            testedCount++
                            val start = System.currentTimeMillis()
                            try {
                                withTimeout(20_000) {
                                    callModelApiDirect(m, "Reply with OK", null)
                                }
                                val ms = System.currentTimeMillis() - start
                                if (m.status != ModelStatus.FROZEN && m.status != ModelStatus.MANUALLY_STOPPED) {
                                    m.status = ModelStatus.AVAILABLE
                                }
                                m.lastCheckOk = true
                                m.lastCheckMessage = "متصل (${ms}ms)"
                                Triple(m, "✅ جاهز (${ms}ms)", true)
                            } catch (e: CancellationException) {
                                m.lastCheckOk = false
                                m.lastCheckMessage = "أُلغي الفحص"
                                Triple(m, "⏹️ أُلغي الفحص", false)
                            } catch (e: Exception) {
                                val err = safeError(e)
                                if (m.status != ModelStatus.FROZEN && m.status != ModelStatus.MANUALLY_STOPPED) {
                                    m.status = ModelStatus.UNAVAILABLE
                                }
                                m.lastCheckOk = false
                                m.lastCheckMessage = err
                                Triple(m, "❌ فشل: $err", false)
                            }
                        }
                    }
                }
            }.awaitAll()

            deferredChecks.forEach { (m, msg, ok) ->
                if (ok) readyCount++
                reportLines.add("• ${m.name} [${m.provider} | ${m.modelId}]:\n$msg")
            }

            saveModels()
            updateLeaderButtonLabel()
            resultTextView.text = "--- 📊 تقرير فحص الجاهزية الفعلي ---\n\n" + reportLines.joinToString("\n\n")
            setBusy(
                false,
                "اكتمل الفحص: $readyCount جاهز من أصل $testedCount نموذج مفحوص.",
                if (readyCount > 0) "#4ADE80" else "#F87171"
            )
            btnReadinessCheck.isEnabled = true
        }
    }

    // ==================== محرك المجلس والتقييم والـ Checkpoints ====================

    private fun assertConversationActiveOrThrow(conv: ConversationRecord, stageName: String) {
        if (deletedConversationIds.contains(conv.conversationId) || conv.state == ConversationState.DELETING) {
            throw CancellationException("المحادثة حُذفت.")
        }
        if (conv.state == ConversationState.PAUSED) {
            throw PausedWorkflowException(stageName)
        }
    }

    private fun startCouncilWorkflow() {
        val conv = getCurrentConversation() ?: return
        val convId = conv.conversationId

        if (conv.state == ConversationState.PAUSED) {
            toast("المحادثة في حالة إيقاف مؤقت (PAUSED). اضغط [▶️ استئناف] أولاً.")
            return
        }
        if (conversationJobs[convId]?.isActive == true) {
            toast("يوجد مجلس قيد التنفيذ بالفعل لهذه المحادثة.")
            return
        }

        val rawPrompt = promptInput.text.toString().trim()
        if (rawPrompt.isBlank()) {
            toast("يرجى كتابة المهمة أولاً.")
            return
        }

        val eligibleModels = models.filter { isModelEligible(it) }
        if (eligibleModels.isEmpty()) {
            toast("لا توجد نماذج مؤهلة (تأكد من وجود مفتاح صالح وعدم تجميد أو إيقاف جميع النماذج).")
            showModelsManagerDialog()
            return
        }

        if (conv.engineeredPrompt != rawPrompt) {
            conv.lastCompletedStage = 0
            conv.cachedPlan = ""
            conv.cachedProposalsJson = JSONArray()
            conv.cachedReviewsJson = JSONArray()
        }

        conv.engineeredPrompt = rawPrompt
        conv.updatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        saveConversationToFile(conv)
        if (currentConvId == convId) decisionLayout.visibility = View.GONE

        val job = scope.launch {
            if (currentConvId == convId) {
                btnStartCouncil.isEnabled = false
                setBusy(true, "🚀 بدء خط أنابيب المجلس للمحادثة (${conv.title})...", "#38BDF8")
            }

            val stageLog = StringBuilder()
            stageLog.append("=== سجل تنفيذ المجلس (${conv.title}) ===\n")
            stageLog.append("• وقت البدء: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}\n")

            try {
                assertConversationActiveOrThrow(conv, "قبل المرحلة 1 (فحص النماذج)")

                // --- المرحلة 1: التحقق من النماذج النشطة ---
                updateUiForConv(convId, "المرحلة 1/5: فحص استجابة النماذج المؤهلة...", "#38BDF8")
                val healthyModels = mutableListOf<AiModel>()
                val preflightChecks = eligibleModels.map { m ->
                    async(Dispatchers.IO) {
                        try {
                            withTimeout(15_000) { callModelApiDirect(m, "Reply with OK", convId) }
                            Pair(m, null as String?)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Pair(m, safeError(e))
                        }
                    }
                }.awaitAll()

                preflightChecks.forEach { (m, err) ->
                    if (err == null && m.status != ModelStatus.FROZEN && m.status != ModelStatus.MANUALLY_STOPPED) {
                        healthyModels.add(m)
                        stageLog.append("✅ [مرحلة 1] ${m.name}: متاح ومستجيب.\n")
                    } else {
                        stageLog.append("❌ [مرحلة 1] ${m.name}: استُبعد (${err ?: m.status.name}).\n")
                    }
                }

                if (healthyModels.isEmpty()) {
                    throw IllegalStateException("فشلت جميع النماذج المؤهلة في اجتياز فحص الاتصال بالمرحلة 1.")
                }
                if (conv.lastCompletedStage < 1) {
                    conv.lastCompletedStage = 1
                    conv.stoppingPoint = "اكتملت المرحلة 1/5 (فحص الجاهزية)"
                    saveConversationToFile(conv)
                }

                assertConversationActiveOrThrow(conv, "بعد المرحلة 1")

                // اختيار القائد المنسق مع Failover صريح
                var leader = if (manualLeaderId != "AUTO") {
                    healthyModels.find { it.id == manualLeaderId }
                } else null
                if (leader == null) {
                    leader = healthyModels.firstOrNull { it.role == "Planner/Leader" } ?: healthyModels.first()
                }

                // --- المرحلة 2: إعداد خطة التنفيذ (أو استرجاعها عند الاستئناف) ---
                val planText: String = if (conv.lastCompletedStage >= 2 && conv.cachedPlan.isNotBlank()) {
                    stageLog.append("⏩ [مرحلة 2] استُرجعت الخطة المحفوظة مسبقاً دون إعادة طلب.\n")
                    conv.cachedPlan
                } else {
                    updateUiForConv(convId, "المرحلة 2/5: القائد (${leader.name}) يعد خطة التنفيذ...", "#38BDF8")
                    val generatedPlan = try {
                        withTimeout(60_000) {
                            callModelApiDirect(
                                leader,
                                "حلّل المهمة التالية وضع خطة تنفيذ تقنية موجزة ومعايير قبول دقيقة:\n$rawPrompt",
                                convId
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val backupLeader = healthyModels.firstOrNull { it.id != leader.id && isModelEligible(it) }
                            ?: throw IllegalStateException("فشل القائد (${leader.name}) في وضع الخطة ولا يوجد بديل متاح: ${safeError(e)}")
                        stageLog.append("⚠️ [مرحلة 2] فشل القائد ${leader.name} (${safeError(e)}) -> انتقل الدور للبديل ${backupLeader.name}.\n")
                        leader = backupLeader
                        withTimeout(60_000) {
                            callModelApiDirect(
                                leader,
                                "حلّل المهمة التالية وضع خطة تنفيذ تقنية موجزة ومعايير قبول دقيقة:\n$rawPrompt",
                                convId
                            )
                        }
                    }
                    conv.cachedPlan = generatedPlan
                    conv.lastCompletedStage = 2
                    conv.stoppingPoint = "اكتملت المرحلة 2/5 (إعداد الخطة)"
                    saveConversationToFile(conv)
                    stageLog.append("✅ [مرحلة 2] أعدّ القائد (${leader.name}) الخطة بنجاح.\n")
                    generatedPlan
                }

                assertConversationActiveOrThrow(conv, "بعد المرحلة 2 (الخطة)")

                // --- المرحلة 3: توليد المقترحات البرمجية ---
                val validProposals: List<Proposal> = if (conv.lastCompletedStage >= 3 && conv.cachedProposalsJson.length() > 0) {
                    stageLog.append("⏩ [مرحلة 3] استُرجعت المقترحات المحفوظة مسبقاً.\n")
                    val restored = mutableListOf<Proposal>()
                    for (i in 0 until conv.cachedProposalsJson.length()) {
                        val o = conv.cachedProposalsJson.getJSONObject(i)
                        restored.add(Proposal(o.getString("modelId"), o.getString("modelName"), o.getString("text"), null))
                    }
                    restored
                } else {
                    updateUiForConv(convId, "المرحلة 3/5: توليد المقترحات البرمجية بالتوازي...", "#38BDF8")
                    val currentEligible = healthyModels.filter { isModelEligible(it) }
                    if (currentEligible.isEmpty()) throw IllegalStateException("تم إيقاف أو تجميد جميع النماذج قبل المرحلة 3.")

                    val rawProposals = currentEligible.map { m ->
                        async(Dispatchers.IO) {
                            try {
                                val code = withTimeout(85_000) {
                                    callModelApiDirect(
                                        m,
                                        "أنت مطور برمجيات في مجلس Bassel AI. نفّذ المهمة التالية وفق الخطة، وضع كل ملف برمجي داخل كتلة ```filename.ext واضحة:\n\nالمهمة:\n$rawPrompt\n\nالخطة:\n$planText",
                                        convId
                                    )
                                }
                                Proposal(m.id, m.name, code, null)
                            } catch (e: CancellationException) {
                                Proposal(m.id, m.name, "", "أُلغي الطلب")
                            } catch (e: Exception) {
                                Proposal(m.id, m.name, "", safeError(e))
                            }
                        }
                    }.awaitAll()

                    val succeeded = rawProposals.filter { it.error == null && it.text.isNotBlank() }
                    rawProposals.forEach { p ->
                        if (p.error == null && p.text.isNotBlank()) {
                            stageLog.append("✅ [مرحلة 3] مقترح ${p.modelName}: اكتمل (${p.text.length} حرف).\n")
                        } else {
                            stageLog.append("❌ [مرحلة 3] مقترح ${p.modelName}: فشل (${p.error}).\n")
                        }
                    }
                    if (succeeded.isEmpty()) throw IllegalStateException("فشلت جميع النماذج في توليد أي مقترح صالح في المرحلة 3.")

                    val arr = JSONArray()
                    succeeded.forEach { p ->
                        arr.put(JSONObject().put("modelId", p.modelId).put("modelName", p.modelName).put("text", p.text))
                    }
                    conv.cachedProposalsJson = arr
                    conv.lastCompletedStage = 3
                    conv.stoppingPoint = "اكتملت المرحلة 3/5 (${succeeded.size} مقترحات)"
                    saveConversationToFile(conv)
                    succeeded
                }

                assertConversationActiveOrThrow(conv, "بعد المرحلة 3 (المقترحات)")

                // --- المرحلة 4: المراجعة المستقلة والتقييم الصارم (بدون أي درجات افتراضية) ---
                val validReviews: List<ReviewerReport> = if (conv.lastCompletedStage >= 4 && conv.cachedReviewsJson.length() > 0) {
                    stageLog.append("⏩ [مرحلة 4] استُرجعت تقارير المراجعة المحفوظة.\n")
                    val restored = mutableListOf<ReviewerReport>()
                    for (i in 0 until conv.cachedReviewsJson.length()) {
                        val o = conv.cachedReviewsJson.getJSONObject(i)
                        restored.add(ReviewerReport(o.getString("reviewerId"), o.getString("reviewerName"), o.getString("text"), null))
                    }
                    restored
                } else {
                    updateUiForConv(convId, "المرحلة 4/5: المراجعة المتبادلة وتقييم المرشحين...", "#FBBF24")
                    val reviewers = healthyModels.filter { isModelEligible(it) }
                    val revReports = reviewers.map { rev ->
                        async(Dispatchers.IO) {
                            try {
                                val reviewPrompt = buildString {
                                    append("أنت مراجع كود مستقل. قيّم كل مرشح من 0 إلى 100 بدقة، واكتب لكل مرشح سطراً مستقبلاً بالصيغة الحرفية:\n")
                                    append("CANDIDATE_<رقم>_SCORE: <الدرجة من 0 إلى 100>\n")
                                    append("ثم اذكر الملاحظات والعيوب التقنية باختصار.\n\n")
                                    validProposals.forEachIndexed { idx, prop ->
                                        append("=== CANDIDATE_${idx + 1} (${prop.modelName}) ===\n")
                                        append(prop.text.take(7000)).append("\n\n")
                                    }
                                }
                                val rText = withTimeout(75_000) { callModelApiDirect(rev, reviewPrompt, convId) }
                                ReviewerReport(rev.id, rev.name, rText, null)
                            } catch (e: CancellationException) {
                                ReviewerReport(rev.id, rev.name, "", "أُلغيت المراجعة")
                            } catch (e: Exception) {
                                ReviewerReport(rev.id, rev.name, "", safeError(e))
                            }
                        }
                    }.awaitAll()

                    val okReviews = revReports.filter { it.error == null && it.text.isNotBlank() }
                    revReports.forEach { r ->
                        if (r.error == null) {
                            stageLog.append("✅ [مرحلة 4] مراجعة ${r.reviewerName}: اكتملت.\n")
                        } else {
                            stageLog.append("⚠️ [مرحلة 4] مراجعة ${r.reviewerName}: تعذرت (${r.error}).\n")
                        }
                    }

                    val revArr = JSONArray()
                    okReviews.forEach { r ->
                        revArr.put(JSONObject().put("reviewerId", r.reviewerId).put("reviewerName", r.reviewerName).put("text", r.text))
                    }
                    conv.cachedReviewsJson = revArr
                    conv.lastCompletedStage = 4
                    conv.stoppingPoint = "اكتملت المرحلة 4/5 (المراجعة والتقييم)"
                    saveConversationToFile(conv)
                    okReviews
                }

                val decision = evaluateProposalsStrict(validProposals, validReviews, consensusThreshold)
                stageLog.append("\n--- تفاصيل التقييم والقرار ---\n${decision.detailedAuditLog}\n")

                assertConversationActiveOrThrow(conv, "بعد المرحلة 4 (التقييم)")

                // --- المرحلة 5: الدمج النهائي عبر القائد ---
                updateUiForConv(convId, "المرحلة 5/5: دمج الملاحظات وإخراج النسخة النهائية...", "#38BDF8")
                val finalMerger = if (isModelEligible(leader)) leader else healthyModels.firstOrNull { isModelEligible(it) }
                    ?: throw IllegalStateException("لا يوجد نموذج متاح لإتمام الدمج النهائي في المرحلة 5.")

                val finalOutput = if (validReviews.isNotEmpty()) {
                    val digest = validReviews.joinToString("\n\n") { "مراجعة (${it.reviewerName}):\n${it.text.take(2500)}" }
                    withTimeout(85_000) {
                        callModelApiDirect(
                            finalMerger,
                            "أنت المنسق النهائي. قم بتحسين المقترح المختار أدناه وتصحيح أي عيوب وردت في المراجعات، وأخرج الكود الكامل النهائي داخل كتل ```filename.ext:\n\n" +
                                    "المقترح المختار (${decision.selectedProposal.modelName}):\n${decision.selectedProposal.text}\n\n" +
                                    "المراجعات:\n$digest",
                            convId
                        )
                    }
                } else {
                    stageLog.append("ℹ️ لم تتوفر مراجعات نصية صالحة؛ اعتمد المقترح المباشر دون ادعاء تقييم.\n")
                    decision.selectedProposal.text
                }

                assertConversationActiveOrThrow(conv, "عند حفظ المرحلة 5")

                conv.finalCode = finalOutput
                conv.reviewScore = decision.averageScore
                conv.consensusAchieved = decision.consensusReached
                conv.lastCompletedStage = 5
                conv.stoppingPoint = decision.decisionLabel
                conv.auditReport = stageLog.toString() + "\nالنتيجة: ${decision.decisionLabel}"
                saveConversationToFile(conv)

                if (currentConvId == convId) {
                    openConversation(convId)
                    setStatus("✅ ${decision.decisionLabel}", if (decision.consensusReached) "#4ADE80" else "#38BDF8")
                }

            } catch (p: PausedWorkflowException) {
                conv.stoppingPoint = p.message ?: "متوقفة مؤقتاً"
                conv.auditReport = stageLog.toString() + "\n⏸️ ${p.message}"
                saveConversationToFile(conv)
                if (currentConvId == convId) {
                    updateConversationBannerUI(conv)
                    setStatus("⏸️ ${p.message} — اضغط [▶️ استئناف] ثم [🚀 بدء المجلس] للمتابعة.", "#FBBF24")
                }
            } catch (ce: CancellationException) {
                if (!deletedConversationIds.contains(convId)) {
                    conv.stoppingPoint = "أُلغي التنفيذ عند المرحلة ${conv.lastCompletedStage}/5"
                    conv.auditReport = stageLog.toString() + "\n⏹️ أُلغي التنفيذ."
                    saveConversationToFile(conv)
                    if (currentConvId == convId) {
                        setStatus("⏹️ أُلغي تنفيذ المهمة.", "#F87171")
                    }
                }
            } catch (e: Exception) {
                val err = safeError(e)
                if (!deletedConversationIds.contains(convId)) {
                    conv.stoppingPoint = "فشل: $err"
                    conv.auditReport = stageLog.toString() + "\n❌ خطأ توقف: $err"
                    saveConversationToFile(conv)
                    if (currentConvId == convId) {
                        resultTextView.text = "❌ توقف المجلس بسبب خطأ:\n$err\n\n--- سجل المراحل ---\n$stageLog"
                        setStatus("❌ فشل المجلس: $err", "#F87171")
                    }
                }
            } finally {
                conversationJobs.remove(convId)
                if (currentConvId == convId) {
                    btnStartCouncil.isEnabled = true
                    progressBar.visibility = View.GONE
                }
            }
        }
        conversationJobs[convId] = job
    }

    private fun updateUiForConv(convId: String, statusMsg: String, colorHex: String) {
        if (currentConvId == convId) {
            setStatus(statusMsg, colorHex)
        }
    }

    private fun evaluateProposalsStrict(
        proposals: List<Proposal>,
        validReviews: List<ReviewerReport>,
        threshold: Int
    ): EvaluationDecision {
        val requiredQuorum = maxOf(1, (validReviews.size + 1) / 2)
        val auditSb = StringBuilder()

        if (validReviews.isEmpty()) {
            val chosen = proposals.first()
            val label = "مكتمل بدون تقييم (التقييم غير متاح لعدم نجاح أي مراجعة)"
            auditSb.append("• لا توجد مراجعات صالحة؛ لم يتم توليد أي درجة افتراضية.\n")
            return EvaluationDecision(chosen, null, 0, requiredQuorum, false, label, auditSb.toString())
        }

        val candidatesList = proposals.mapIndexed { idx, prop ->
            val candNo = idx + 1
            val extractedScores = mutableListOf<Int>()
            for (rev in validReviews) {
                val sc = parseScoreFromReview(rev.text, candNo)
                if (sc != null) {
                    extractedScores.add(sc)
                    auditSb.append("• المراجع (${rev.reviewerName}) منح المرشح #$candNo (${prop.modelName}):$sc/100\n")
                } else {
                    auditSb.append("• المراجع (${rev.reviewerName}) لم يقدم درجة صالحة للمرشح #$candNo\n")
                }
            }
            val avg = if (extractedScores.isNotEmpty()) extractedScores.average() else null
            CandidateScore(prop, extractedScores, avg)
        }

        val quorumCandidates = candidatesList.filter { it.average != null && it.scores.size >= requiredQuorum }
        val winner = quorumCandidates.maxByOrNull { it.average!! }
            ?: candidatesList.filter { it.average != null }.maxByOrNull { it.average!! }

        if (winner == null || winner.average == null) {
            val fallback = proposals.first()
            val label = "مكتمل (التقييم الرقمي غير متاح لتعذر استخراج درجات صالحة)"
            return EvaluationDecision(fallback, null, 0, requiredQuorum, false, label, auditSb.toString())
        }

        val avgInt = winner.average.toInt().coerceIn(0, 100)
        val allMeetThreshold = winner.scores.all { it >= maxOf(70, threshold - 15) }
        val hasQuorum = winner.scores.size >= requiredQuorum
        val consensusReached = hasQuorum && avgInt >= threshold && allMeetThreshold

        val label = if (consensusReached) {
            "اكتمل مع تحقق معيار الإجماع ($avgInt/100 من ${winner.scores.size} مراجعين)"
        } else {
            "تم اختيار الأعلى تقييماً ($avgInt/100) — دون تحقق معيار الإجماع ($threshold%)"
        }

        return EvaluationDecision(
            selectedProposal = winner.proposal,
            averageScore = avgInt,
            validReviewCount = winner.scores.size,
            requiredQuorum = requiredQuorum,
            consensusReached = consensusReached,
            decisionLabel = label,
            detailedAuditLog = auditSb.toString()
        )
    }

    private fun parseScoreFromReview(text: String, candidateNumber: Int): Int? {
        val regex = Regex("(?im)^\\s*CANDIDATE_${candidateNumber}_SCORE\\s*:\\s*(100|[1-9]?\\d)\\s*$")
        return regex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..100 }
    }

    // ==================== بناء عنوان API والاتصال الشبكي الآمن ====================

    private fun buildTargetEndpoint(model: AiModel): String {
        val rawEndpoint = model.endpoint.trim().trimEnd('/')
        val cleanModelId = model.modelId.trim().removePrefix("models/")

        return if (model.apiFormat == ApiFormat.GEMINI_GENERATE_CONTENT || model.provider.equals("Gemini", ignoreCase = true)) {
            if (rawEndpoint.contains(":generateContent")) {
                rawEndpoint
            } else {
                val base = rawEndpoint
                    .substringBefore("/models/")
                    .trimEnd('/')
                    .let { if (it.endsWith("/v1beta") || it.endsWith("/v1")) it else "$it/v1beta" }
                "$base/models/$cleanModelId:generateContent"
            }
        } else {
            rawEndpoint
        }
    }

    private suspend fun callModelApiDirect(
        model: AiModel,
        prompt: String,
        conversationId: String?
    ): String = withContext(Dispatchers.IO) {
        if (model.status == ModelStatus.FROZEN || model.status == ModelStatus.MANUALLY_STOPPED) {
            throw IllegalStateException("النموذج ${model.name} في حالة ${model.status.name} ولا يمكن إرسال طلبات إليه.")
        }

        val keyResult = decryptKeyDetailed(model.encryptedKey)
        val key = when (keyResult) {
            is KeyDecryptResult.Success -> keyResult.plainKey.trim()
            is KeyDecryptResult.Empty -> throw IllegalStateException("مفتاح API غير مضبوط للنموذج ${model.name}")
            is KeyDecryptResult.CorruptedData -> throw IllegalStateException("مفتاح API تالف للنموذج ${model.name}:${keyResult.reason}")
            is KeyDecryptResult.KeystoreError -> throw IllegalStateException("خطأ Keystore للنموذج ${model.name}:${keyResult.reason}")
        }

        val targetUrl = buildTargetEndpoint(model)
        val isGemini = model.apiFormat == ApiFormat.GEMINI_GENERATE_CONTENT || model.provider.equals("Gemini", ignoreCase = true)

        val requestJson = if (isGemini) {
            JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("temperature", 0.2))
        } else {
            JSONObject()
                .put("model", model.modelId.trim())
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", "You are a precise software engineering agent in Bassel AI Council."))
                        .put(JSONObject().put("role", "user").put("content", prompt))
                )
                .put("temperature", 0.2)
        }

        val reqBuilder = Request.Builder()
            .url(targetUrl)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")

        when {
            isGemini || model.authType == AuthType.GOOGLE_API_KEY_HEADER -> {
                reqBuilder.addHeader("x-goog-api-key", key)
            }
            model.authType == AuthType.X_API_KEY_HEADER -> {
                reqBuilder.addHeader("x-api-key", key)
            }
            else -> {
                reqBuilder.addHeader("Authorization", "Bearer $key")
            }
        }

        if (model.provider.equals("OpenRouter", ignoreCase = true)) {
            reqBuilder.addHeader("HTTP-Referer", "https://bassel.ai.local")
            reqBuilder.addHeader("X-Title", "Bassel AI")
        }

        val requestId = UUID.randomUUID().toString()
        val call = client.newCall(reqBuilder.build())
        activeHttpCalls[requestId] = TrackedHttpCall(requestId, conversationId, model.id, call)

        if (model.status == ModelStatus.AVAILABLE) {
            model.status = ModelStatus.RUNNING
        }

        try {
            call.execute().use { response ->
                val rawBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errDetail = try {
                        JSONObject(rawBody).optJSONObject("error")?.optString("message") ?: rawBody.take(220)
                    } catch (_: Exception) {
                        rawBody.take(220)
                    }
                    throw IOException("HTTP ${response.code} (${model.modelId}):$errDetail")
                }

                if (rawBody.isBlank()) {
                    throw IOException("استجابة فارغة من المزود (${model.name}).")
                }

                val json = JSONObject(rawBody)
                val parsedText = if (isGemini) {
                    json.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text")
                } else {
                    json.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                }

                if (parsedText.isNullOrBlank()) {
                    throw IOException("رد JSON من (${model.name}) لا يحتوي على حقل نصي صالح.")
                }

                if (model.status == ModelStatus.RUNNING) {
                    model.status = ModelStatus.AVAILABLE
                }
                return@withContext parsedText
            }
        } catch (e: IOException) {
            if (call.isCanceled() || !coroutineContext.isActive) {
                throw CancellationException("أُلغي الاتصال الشبكي للنموذج ${model.name}.")
            }
            if (model.status == ModelStatus.RUNNING) {
                model.status = ModelStatus.FAILED
            }
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (model.status == ModelStatus.RUNNING) {
                model.status = ModelStatus.FAILED
            }
            throw e
        } finally {
            activeHttpCalls.remove(requestId)
            if (model.status == ModelStatus.RUNNING) {
                model.status = ModelStatus.AVAILABLE
            }
        }
    }
    // ==================== إدارة النماذج، القوالب، التجميد، والإيقاف ====================

    private fun showModelsManagerDialog() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val dialogRef = arrayOfNulls<AlertDialog>(1)

        val btnAdd = Button(this).apply {
            text = "➕ إضافة نموذج جديد (من القوالب أو مخصص)"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            background = createRoundedDrawable("#2563EB", 10f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                dialogRef[0]?.dismiss()
                showModelEditor(null)
            }
        }
        container.addView(btnAdd)
        container.addView(createSpacer(10))

        models.forEach { m ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = createRoundedDrawable("#1E293B", 10f)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }

            val keyStateLabel = when (val kr = decryptKeyDetailed(m.encryptedKey)) {
                is KeyDecryptResult.Success -> "🔑 مفتاح مشفر صالح"
                is KeyDecryptResult.Empty -> "🔴 بدون مفتاح"
                is KeyDecryptResult.CorruptedData -> "⚠️ مفتاح تالف (${kr.reason})"
                is KeyDecryptResult.KeystoreError -> "⚠️ خطأ Keystore"
            }

            card.addView(TextView(this).apply {
                text = "${m.name} • ${m.provider} [${m.status.name}]"
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                textSize = 13f
            })

            card.addView(TextView(this).apply {
                text = "الدور: ${m.role} | المعرف: ${m.modelId}\n$keyStateLabel | آخر فحص: ${m.lastCheckMessage}"
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 11.5f
                setPadding(0, dp(4), 0, dp(8))
            })

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 4f
            }

            val btnEdit = Button(this).apply {
                text = "✏️ تعديل"
                textSize = 10.5f
                setTextColor(Color.WHITE)
                isAllCaps = false
                background = createRoundedDrawable("#3B82F6", 6f)
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(2) }
                setOnClickListener {
                    dialogRef[0]?.dismiss()
                    showModelEditor(m)
                }
            }

            val isFrozenOrStopped = m.status == ModelStatus.FROZEN || m.status == ModelStatus.MANUALLY_STOPPED
            val btnFreezeResume = Button(this).apply {
                text = if (isFrozenOrStopped) "▶️ تفعيل" else "❄️ تجميد"
                textSize = 10.5f
                setTextColor(Color.WHITE)
                isAllCaps = false
                background = createRoundedDrawable(if (isFrozenOrStopped) "#16A34A" else "#D97706", 6f)
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                setOnClickListener {
                    if (isFrozenOrStopped) {
                        m.status = ModelStatus.AVAILABLE
                        toast("تم استئناف النموذج ${m.name}.")
                    } else {
                        m.status = ModelStatus.FROZEN
                        toast("تم تجميد النموذج ${m.name} عن المهام الجديدة.")
                    }
                    saveModels()
                    updateLeaderButtonLabel()
                    dialogRef[0]?.dismiss()
                    showModelsManagerDialog()
                }
            }

            val btnStop = Button(this).apply {
                text = "⏹️ إيقاف"
                textSize = 10.5f
                setTextColor(Color.WHITE)
                isAllCaps = false
                background = createRoundedDrawable("#DC2626", 6f)
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                setOnClickListener {
                    val cancelledCount = cancelCallsForModel(m.id)
                    m.status = ModelStatus.MANUALLY_STOPPED
                    saveModels()
                    updateLeaderButtonLabel()
                    toast("تم إيقاف ${m.name} وإلغاء $cancelledCount طلب شبكي جارٍ.")
                    dialogRef[0]?.dismiss()
                    showModelsManagerDialog()
                }
            }

            val btnDel = Button(this).apply {
                text = "🗑️ حذف"
                textSize = 10.5f
                setTextColor(Color.WHITE)
                isAllCaps = false
                background = createRoundedDrawable("#7F1D1D", 6f)
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(2) }
                setOnClickListener {
                    if (models.size <= 1) {
                        toast("يجب الإبقاء على نموذج واحد على الأقل.")
                        return@setOnClickListener
                    }
                    cancelCallsForModel(m.id)
                    models.removeAll { it.id == m.id }
                    if (manualLeaderId == m.id) manualLeaderId = "AUTO"
                    saveModels()
                    updateLeaderButtonLabel()
                    dialogRef[0]?.dismiss()
                    showModelsManagerDialog()
                }
            }

            row.addView(btnEdit)
            row.addView(btnFreezeResume)
            row.addView(btnStop)
            row.addView(btnDel)
            card.addView(row)
            container.addView(card)
        }

        scroll.addView(container)
        val dialog = AlertDialog.Builder(this)
            .setTitle("⚙️ إدارة النماذج والمفاتيح")
            .setView(scroll)
            .setPositiveButton("إغلاق", null)
            .create()
        dialogRef[0] = dialog
        dialog.show()
    }

    private fun showModelEditor(existing: AiModel?) {
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        box.addView(createDialogLabel("قالب سريع جاهز (اختياري لملء الإعدادات تلقائياً):"))
        val templateNames = listOf("-- اختر قالباً جاهزاً أو عدّل يدوياً --") + DEFAULT_TEMPLATES.map { it.templateName }
        val templateSpinner = Spinner(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, templateNames)
        }
        box.addView(templateSpinner)
        box.addView(createSpacer(8))

        box.addView(createDialogLabel("المزود (Provider):"))
        val providerSpinner = Spinner(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, PROVIDERS_LIST)
        }
        if (existing != null) {
            providerSpinner.setSelection(PROVIDERS_LIST.indexOf(existing.provider).coerceAtLeast(0))
        }
        box.addView(providerSpinner)
        box.addView(createSpacer(8))

        box.addView(createDialogLabel("اسم العرض:"))
        val nameF = createDialogInput("مثال: Groq Llama 3.3", existing?.name ?: "")
        box.addView(nameF)
        box.addView(createSpacer(8))

        box.addView(createDialogLabel("رابط Endpoint URL:"))
        val epF = createDialogInput("https://...", existing?.endpoint ?: DEFAULT_GROQ_ENDPOINT)
        box.addView(epF)
        box.addView(createSpacer(8))

        box.addView(createDialogLabel("معرف النموذج (Model ID):"))
        val midF = createDialogInput("مثال: llama-3.3-70b-versatile", existing?.modelId ?: "")
        box.addView(midF)
        box.addView(createSpacer(8))

        box.addView(createDialogLabel("الدور التخصصي في المجلس (Role):"))
        val roleSpinner = Spinner(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ROLES_LIST)
        }
        if (existing != null) {
            roleSpinner.setSelection(ROLES_LIST.indexOf(existing.role).coerceAtLeast(0))
        }
        box.addView(roleSpinner)
        box.addView(createSpacer(8))

        val formats = ApiFormat.values().map { it.name }
        box.addView(createDialogLabel("صيغة البروتوكول (API Format):"))
        val formatSpinner = Spinner(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, formats)
        }
        if (existing != null) {
            formatSpinner.setSelection(formats.indexOf(existing.apiFormat.name).coerceAtLeast(0))
        }
        box.addView(formatSpinner)
        box.addView(createSpacer(8))

        val authTypes = AuthType.values().map { it.name }
        box.addView(createDialogLabel("طريقة المصادقة (Auth Header):"))
        val authSpinner = Spinner(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, authTypes)
        }
        if (existing != null) {
            authSpinner.setSelection(authTypes.indexOf(existing.authType.name).coerceAtLeast(0))
        }
        box.addView(authSpinner)
        box.addView(createSpacer(8))

        box.addView(createDialogLabel("مفتاح API (اتركه فارغاً للاحتفاظ بالمفتاح المشفر الحالي):"))
        val keyF = createDialogInput("ألصق مفتاح الـ API هنا...", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(keyF)

        templateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position <= 0) return
                val tpl = DEFAULT_TEMPLATES[position - 1]
                providerSpinner.setSelection(PROVIDERS_LIST.indexOf(tpl.provider).coerceAtLeast(0))
                nameF.setText(tpl.defaultDisplayName)
                epF.setText(tpl.defaultEndpoint)
                midF.setText(tpl.modelId)
                roleSpinner.setSelection(ROLES_LIST.indexOf(tpl.suggestedRole).coerceAtLeast(0))
                formatSpinner.setSelection(formats.indexOf(tpl.apiFormat.name).coerceAtLeast(0))
                authSpinner.setSelection(authTypes.indexOf(tpl.authType.name).coerceAtLeast(0))
            }
        }

        scroll.addView(box)
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "➕ إضافة نموذج جديد" else "✏️ تعديل النموذج")
            .setView(scroll)
            .setNegativeButton("إلغاء") { _, _ -> showModelsManagerDialog() }
            .setPositiveButton("💾 حفظ") { _, _ ->
                val nameVal = nameF.text.toString().trim()
                val epVal = epF.text.toString().trim()
                val midVal = midF.text.toString().trim()
                val rawKey = keyF.text.toString().trim()

                if (nameVal.isBlank() || epVal.isBlank() || midVal.isBlank() || !epVal.startsWith("http")) {
                    toast("يرجى إدخال اسم، ومعرف نموذج، ورابط Endpoint يبدأ بـ http/https.")
                    return@setPositiveButton
                }

                val finalEncryptedKey = if (rawKey.isNotBlank()) {
                    try {
                        encryptKeyOrThrow(rawKey)
                    } catch (e: KeyCryptoException) {
                        AlertDialog.Builder(this)
                            .setTitle("❌ فشل تشفير المفتاح")
                            .setMessage("لم يتم حفظ النموذج لأن تشفير مفتاح API في Android Keystore فشل:\n${safeError(e)}")
                            .setPositiveButton("حسناً", null)
                            .show()
                        return@setPositiveButton
                    }
                } else {
                    existing?.encryptedKey.orEmpty()
                }

                val selectedProvider = PROVIDERS_LIST[providerSpinner.selectedItemPosition]
                val selectedRole = ROLES_LIST[roleSpinner.selectedItemPosition]
                val selectedFormat = ApiFormat.valueOf(formats[formatSpinner.selectedItemPosition])
                val selectedAuth = AuthType.valueOf(authTypes[authSpinner.selectedItemPosition])

                val updatedModel = AiModel(
                    id = existing?.id ?: "model_${System.currentTimeMillis()}",
                    name = nameVal,
                    provider = selectedProvider,
                    endpoint = epVal,
                    modelId = midVal,
                    encryptedKey = finalEncryptedKey,
                    role = selectedRole,
                    status = existing?.status ?: ModelStatus.AVAILABLE,
                    apiFormat = selectedFormat,
                    authType = selectedAuth,
                    lastCheckOk = existing?.lastCheckOk,
                    lastCheckMessage = existing?.lastCheckMessage ?: "لم يُفحص بعد"
                )

                val existingIdx = models.indexOfFirst { it.id == updatedModel.id }
                if (existingIdx >= 0) {
                    models[existingIdx] = updatedModel
                } else {
                    models.add(updatedModel)
                }
                saveModels()
                updateLeaderButtonLabel()
                toast("تم حفظ إعدادات النموذج بنجاح.")
                showModelsManagerDialog()
            }
            .show()
    }

    private fun showLeaderSelectorDialog() {
        val labels = mutableListOf<String>()
        val ids = mutableListOf<String>()

        labels.add("👑 تلقائي ذكي (Planner/Leader المؤهل + Failover)")
        ids.add("AUTO")

        models.forEach { m ->
            val eligible = isModelEligible(m)
            val tag = if (eligible) "🟢 مؤهل" else "🚫 غير مؤهل (${m.status.name})"
            labels.add("👤 ${m.name} [$tag]")
            ids.add(m.id)
        }

        AlertDialog.Builder(this)
            .setTitle("👑 اختيار قائد المجلس")
            .setItems(labels.toTypedArray()) { _, which ->
                val chosenId = ids[which]
                if (chosenId != "AUTO") {
                    val chosenModel = models.find { it.id == chosenId }
                    if (chosenModel != null && !isModelEligible(chosenModel)) {
                        toast("لا يمكن تعيين نموذج مجمّد أو متوقف أو بدون مفتاح صالح كقائد.")
                        return@setItems
                    }
                }
                manualLeaderId = chosenId
                saveModels()
                updateLeaderButtonLabel()
            }
            .show()
    }

    private fun updateLeaderButtonLabel() {
        if (!::btnModeSelector.isInitialized) return
        if (manualLeaderId == "AUTO") {
            val autoCandidate = models.firstOrNull { isModelEligible(it) && it.role == "Planner/Leader" }
                ?: models.firstOrNull { isModelEligible(it) }
            btnModeSelector.text = "👑 مدير المجلس: تلقائي (${autoCandidate?.name ?: "بانتظار مفتاح صالح"})"
        } else {
            val selected = models.find { it.id == manualLeaderId }
            if (selected == null || !isModelEligible(selected)) {
                manualLeaderId = "AUTO"
                btnModeSelector.text = "👑 مدير المجلس: تلقائي (تم استبعاد القائد السابق لعدم أهليته)"
            } else {
                btnModeSelector.text = "👑 مدير المجلس المعين: (${selected.name})"
            }
        }
    }

    // ==================== إنشاء وتصدير الـ ZIP الآمن ====================

    private fun createZip(autoExport: Boolean) {
        val conv = getCurrentConversation() ?: return
        val source = conv.finalCode.ifBlank { resultTextView.text.toString() }.trim()
        if (source.isBlank()) {
            toast("لا يوجد كود أو مخرجات لإنشاء ملف ZIP.")
            return
        }

        scope.launch {
            setBusy(true, "📦 جاري بناء ملف الـ ZIP وتطبيع المسارات بأمان...")
            try {
                val createdFile = withContext(Dispatchers.IO) {
                    val extDir = getExternalFilesDir(null) ?: filesDir
                    val zipFile = File(extDir, "BasselAI_${conv.conversationId}.zip")
                    val usedEntries = mutableSetOf<String>()

                    ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zos ->
                        val extractedFiles = splitGeneratedFiles(source)
                        extractedFiles.forEachIndexed { idx, (rawPath, content) ->
                            val safeEntryPath = sanitizeAndDeduplicateZipPath(rawPath, usedEntries, idx + 1)
                            zos.putNextEntry(ZipEntry(safeEntryPath))
                            zos.write(content.toByteArray(StandardCharsets.UTF_8))
                            zos.closeEntry()
                        }

                        val auditEntry = sanitizeAndDeduplicateZipPath("AUDIT_REPORT.txt", usedEntries, 999)
                        zos.putNextEntry(ZipEntry(auditEntry))
                        zos.write(conv.auditReport.ifBlank { "لا يوجد تقرير تدقيق." }.toByteArray(StandardCharsets.UTF_8))
                        zos.closeEntry()

                        val memEntry = sanitizeAndDeduplicateZipPath("CONVERSATION_MEMORY.json", usedEntries, 1000)
                        val safeJson = JSONObject().apply {
                            put("conversationId", conv.conversationId)
                            put("title", conv.title)
                            put("updatedAt", conv.updatedAt)
                            put("stoppingPoint", conv.stoppingPoint)
                            put("reviewScore", conv.reviewScore ?: JSONObject.NULL)
                            put("consensusAchieved", conv.consensusAchieved)
                        }
                        zos.putNextEntry(ZipEntry(memEntry))
                        zos.write(safeJson.toString(2).toByteArray(StandardCharsets.UTF_8))
                        zos.closeEntry()
                    }
                    if (!zipFile.exists() || zipFile.length() <= 0L) {
                        throw IOException("ملف الـ ZIP الناتج فارغ أو لم يُنشأ.")
                    }
                    zipFile
                }

                latestZip = createdFile
                setStatus("📦 تم إنشاء ملف الـ ZIP بنجاح (${createdFile.length()} بايت).", "#4ADE80")
                if (autoExport) {
                    exportZip()
                } else {
                    toast("تم حفظ ملف الـ ZIP داخل مساحة التطبيق.")
                }
            } catch (e: Exception) {
                setStatus("❌ فشل إنشاء الـ ZIP: ${safeError(e)}", "#F87171")
                toast("فشل إنشاء الـ ZIP: ${safeError(e)}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun sanitizeAndDeduplicateZipPath(
        rawPath: String,
        usedPaths: MutableSet<String>,
        fallbackIndex: Int
    ): String {
        val normalized = rawPath.trim()
            .replace("\\", "/")
            .removePrefix("/")
            .replace(Regex("^[a-zA-Z]:/"), "")

        val cleanSegments = normalized.split("/")
            .map { it.trim().replace(Regex("[^a-zA-Z0-9_\\-.]"), "_") }
            .filter { it.isNotBlank() && it != "." && it != ".." }

        val baseCandidate = if (cleanSegments.isEmpty()) {
            "generated_file_$fallbackIndex.txt"
        } else {
            cleanSegments.joinToString("/")
        }

        val virtualRoot = File(filesDir, "zip_virtual_root").canonicalFile
        val resolvedTarget = File(virtualRoot, baseCandidate).canonicalFile
        val safeRelative = if (resolvedTarget.path.startsWith(virtualRoot.path + File.separator)) {
            baseCandidate
        } else {
            "safe_file_$fallbackIndex.txt"
        }

        if (usedPaths.add(safeRelative)) return safeRelative

        val dotIdx = safeRelative.lastIndexOf('.')
        val namePart = if (dotIdx > 0) safeRelative.substring(0, dotIdx) else safeRelative
        val extPart = if (dotIdx > 0) safeRelative.substring(dotIdx) else ""

        var counter = 2
        while (true) {
            val candidate = "${namePart}_$counter$extPart"
            if (usedPaths.add(candidate)) return candidate
            counter++
        }
    }

    private fun splitGeneratedFiles(text: String): List<Pair<String, String>> {
        val fenceRegex = Regex("(?s)```([^\\n`]*)\\n(.*?)```")
        val matches = fenceRegex.findAll(text).toList()
        if (matches.isEmpty()) return listOf("output.txt" to text)

        val pathRegex = Regex("([a-zA-Z0-9_\\-./\\\\]+\\.[a-zA-Z0-9]{1,8})")
        return matches.mapIndexed { idx, match ->
            val header = match.groupValues[1].trim()
            val body = match.groupValues[2].trimEnd()

            val headerPath = pathRegex.find(header)?.groupValues?.getOrNull(1)
            val firstLine = body.lineSequence().firstOrNull()?.trim() ?: ""
            val commentPath = if (headerPath == null && (firstLine.startsWith("//") || firstLine.startsWith("#"))) {
                pathRegex.find(firstLine)?.groupValues?.getOrNull(1)
            } else null

            val chosenPath = headerPath ?: commentPath ?: "snippet_${idx + 1}.txt"
            chosenPath to body
        }
    }

    private fun exportZip() {
        val zip = latestZip
        if (zip == null || !zip.exists() || zip.length() <= 0L) {
            createZip(autoExport = true)
            return
        }
        scope.launch {
            setBusy(true, "📥 جاري تصدير الـ ZIP إلى مجلد Downloads...")
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    withContext(Dispatchers.IO) {
                        val fileName = "BasselAI_${System.currentTimeMillis()}.zip"
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw IOException("تعذر إنشاء سجل الملف في MediaStore Downloads.")
                        try {
                            var copiedBytes = 0L
                            contentResolver.openOutputStream(uri, "w")?.use { out ->
                                zip.inputStream().use { input ->
                                    copiedBytes = input.copyTo(out)
                                }
                                out.flush()
                            } ?: throw IOException("تعذر فتح مجرى الكتابة لملف Downloads.")

                            if (copiedBytes <= 0L) {
                                throw IOException("فشل التحقق من حجم البيانات المنسوخة إلى Downloads.")
                            }

                            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                            contentResolver.update(uri, ready, null, null)
                        } catch (e: Exception) {
                            try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                            throw e
                        }
                    }
                    setStatus("✅ تم تصدير ملف الـ ZIP إلى التنزيلات (Downloads) بنجاح.", "#4ADE80")
                    toast("تم الحفظ في التنزيلات (Downloads)!")
                } else {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/zip"
                        putExtra(Intent.EXTRA_TITLE, "BasselAI_Project.zip")
                    }
                    startActivityForResult(intent, REQUEST_EXPORT)
                }
            } catch (e: Exception) {
                setStatus("❌ فشل تصدير الـ ZIP: ${safeError(e)}", "#F87171")
                toast("فشل التصدير: ${safeError(e)}")
            } finally {
                setBusy(false)
            }
        }
    }

    @Deprecated("Legacy Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EXPORT && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            val zip = latestZip ?: return
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        var bytes = 0L
                        contentResolver.openOutputStream(uri, "w")?.use { out ->
                            zip.inputStream().use { bytes = it.copyTo(out) }
                            out.flush()
                        } ?: throw IOException("تعذر فتح الوجهة المختارة.")
                        if (bytes <= 0L) throw IOException("لم تتم كتابة أي بايتات.")
                    }
                    toast("تم تصدير الـ ZIP بنجاح.")
                } catch (e: Exception) {
                    toast("فشل حفظ الـ ZIP: ${safeError(e)}")
                }
            }
        }
    }

    // ==================== تنقيح الأخطاء والتشفير المحلي Keystore ====================

    private fun safeError(e: Throwable): String {
        val raw = e.message ?: e.javaClass.simpleName ?: "UnknownError"
        val step1 = raw.replace(
            Regex("(?i)\\b(api[_-]?key|authorization|bearer|x-goog-api-key|x-api-key|token)\\b(\\s*[:=]\\s*|\\s+)([^\\s\"',;\\]})]+)"),
            "$1$2[REDACTED]"
        )
        val step2 = step1.replace(
            Regex("\\b(AIza[0-9A-Za-z_\\-]{20,}|gsk_[0-9A-Za-z]{20,}|sk-[0-9A-Za-z_\\-]{20,})\\b"),
            "[REDACTED_KEY]"
        )
        return step2.take(240)
    }

    @Throws(KeyCryptoException::class)
    private fun encryptKeyOrThrow(plainText: String): String {
        val clean = plainText.trim()
        if (clean.isEmpty()) throw KeyCryptoException("المفتاح المدخل فارغ.")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv
            if (iv == null || iv.size != 12) {
                throw KeyCryptoException("طول متجه الابتداء IV غير صالح.")
            }
            val encrypted = cipher.doFinal(clean.toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            if (e is KeyCryptoException) throw e
            throw KeyCryptoException("خطأ Keystore أثناء التشفير: ${e.javaClass.simpleName}", e)
        }
    }

    private fun decryptKeyDetailed(encryptedBase64: String): KeyDecryptResult {
        if (encryptedBase64.isBlank()) return KeyDecryptResult.Empty
        return try {
            val allBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            if (allBytes.size <= 12) {
                return KeyDecryptResult.CorruptedData("حجم البيانات المشفرة أقصر من الحد الأدنى")
            }
            val iv = allBytes.copyOfRange(0, 12)
            val cipherBytes = allBytes.copyOfRange(12, allBytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            val plain = String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
            if (plain.isBlank()) KeyDecryptResult.Empty else KeyDecryptResult.Success(plain)
        } catch (_: IllegalArgumentException) {
            KeyDecryptResult.CorruptedData("ترميز Base64 غير صالح")
        } catch (_: AEADBadTagException) {
            KeyDecryptResult.KeystoreError("تغير مفتاح Keystore أو تلف ختم المصادقة GCM")
        } catch (e: Exception) {
            KeyDecryptResult.KeystoreError(e.javaClass.simpleName)
        }
    }

    private fun decryptKey(encryptedBase64: String): String {
        return (decryptKeyDetailed(encryptedBase64) as? KeyDecryptResult.Success)?.plainKey ?: ""
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val alias = "BasselAI.MasterKey.AES"
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
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
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("provider", m.provider)
                    .put("endpoint", m.endpoint)
                    .put("modelId", m.modelId)
                    .put("encryptedKey", m.encryptedKey)
                    .put("role", m.role)
                    .put("status", m.status.name)
                    .put("apiFormat", m.apiFormat.name)
                    .put("authType", m.authType.name)
                    .put("lastCheckMessage", m.lastCheckMessage)
            )
        }
        prefs.edit()
            .putString("models_master_v4", arr.toString())
            .putString("manual_leader_id", manualLeaderId)
            .apply()
    }

    private fun loadModels() {
        models.clear()
        manualLeaderId = prefs.getString("manual_leader_id", "AUTO") ?: "AUTO"
        try {
            val raw = prefs.getString("models_master_v4", null)
            if (!raw.isNullOrBlank()) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val prov = o.optString("provider", "Groq")
                    val defaultFmt = if (prov.equals("Gemini", true)) ApiFormat.GEMINI_GENERATE_CONTENT else ApiFormat.OPENAI_CHAT_COMPLETIONS
                    val defaultAuth = if (prov.equals("Gemini", true)) AuthType.GOOGLE_API_KEY_HEADER else AuthType.BEARER_HEADER
                    val savedStatus = try {
                        ModelStatus.valueOf(o.optString("status", "AVAILABLE"))
                    } catch (_: Exception) {
                        ModelStatus.AVAILABLE
                    }
                    models.add(
                        AiModel(
                            id = o.getString("id"),
                            name = o.getString("name"),
                            provider = prov,
                            endpoint = o.getString("endpoint"),
                            modelId = o.getString("modelId"),
                            encryptedKey = o.optString("encryptedKey", ""),
                            role = o.optString("role", "Coder"),
                            status = if (savedStatus == ModelStatus.RUNNING) ModelStatus.AVAILABLE else savedStatus,
                            apiFormat = try { ApiFormat.valueOf(o.optString("apiFormat", defaultFmt.name)) } catch (_: Exception) { defaultFmt },
                            authType = try { AuthType.valueOf(o.optString("authType", defaultAuth.name)) } catch (_: Exception) { defaultAuth },
                            lastCheckMessage = o.optString("lastCheckMessage", "لم يُفحص بعد")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            toast("تحذير قراءة سجل النماذج: ${safeError(e)}")
        }

        if (models.isEmpty()) {
            DEFAULT_TEMPLATES.take(4).forEachIndexed { idx, tpl ->
                models.add(
                    AiModel(
                        id = "default_${idx + 1}",
                        name = tpl.defaultDisplayName,
                        provider = tpl.provider,
                        endpoint = tpl.defaultEndpoint,
                        modelId = tpl.modelId,
                        encryptedKey = "",
                        role = tpl.suggestedRole,
                        status = ModelStatus.AVAILABLE,
                        apiFormat = tpl.apiFormat,
                        authType = tpl.authType
                    )
                )
            }
            saveModels()
        }
    }

    // ==================== نوافذ السجل والنسخ والدوال المساعدة ====================

    private fun showLogsAndStatesDialog() {
        val conv = getCurrentConversation()
        val sb = StringBuilder()
        sb.append("=== 📂 المحادثة المفتوحة حالياً ===\n")
        if (conv != null) {
            sb.append("• العنوان: ${conv.title} (${conv.conversationId})\n")
            sb.append("• الحالة: ${conv.state.name}\n")
            sb.append("• آخر مرحلة مكتملة: ${conv.lastCompletedStage}/5\n")
            sb.append("• نقطة التوقف: ${conv.stoppingPoint}\n")
            sb.append("• مهام نشطة: ${if (conversationJobs[conv.conversationId]?.isActive == true) "نعم (قيد التنفيذ)" else "لا"}\n\n")
        }
        sb.append("=== 🤖 حالات جميع النماذج ===\n")
        models.forEach { m ->
            sb.append("• ${m.name} (${m.provider} | ${m.role})\n")
            sb.append("  الحالة: ${m.status.name} | الفحص: ${m.lastCheckMessage}\n")
        }
        sb.append("\n• الطلبات الشبكية الجارية حالياً: ${activeHttpCalls.size}")
        showSelectableTextDialog("📊 السجل وحالة الوكلاء", sb.toString())
    }

    private fun showAuditAndMemoryHubDialog() {
        val conv = getCurrentConversation() ?: return
        val options = arrayOf(
            "📜 عرض تقرير التدقيق الكامل للمحادثة",
            "📋 نسخ الكود النهائي",
            "📋 نسخ تقرير التدقيق"
        )
        AlertDialog.Builder(this)
            .setTitle("📋 التقرير وذاكرة المحادثة")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSelectableTextDialog("📜 تقرير التدقيق", conv.auditReport.ifBlank { "لا يوجد تقرير مسجل بعد." })
                    1 -> copyText("الكود النهائي", conv.finalCode)
                    2 -> copyText("تقرير التدقيق", conv.auditReport)
                }
            }
            .setPositiveButton("إغلاق", null)
            .show()
    }

    private fun showQuickCopyMenuDialog() {
        val conv = getCurrentConversation()
        val options = arrayOf(
            "📋 نسخ النص المعروض في الشاشة",
            "📋 نسخ الكود النهائي للمحادثة",
            "📋 نسخ تقرير التدقيق"
        )
        AlertDialog.Builder(this)
            .setTitle("📋 خيارات النسخ")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyText("محتوى الشاشة", resultTextView.text.toString())
                    1 -> copyText("الكود النهائي", conv?.finalCode?.ifBlank { resultTextView.text.toString() } ?: "")
                    2 -> copyText("تقرير التدقيق", conv?.auditReport ?: "")
                }
            }
            .show()
    }

    private fun showSelectableTextDialog(title: String, content: String) {
        val tv = TextView(this).apply {
            text = content
            textSize = 12.5f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
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

    private fun setBusy(busy: Boolean, msg: String = "", color: String = "#38BDF8") {
        if (::progressBar.isInitialized) {
            progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        }
        if (msg.isNotBlank()) setStatus(msg, color)
    }

    private fun setStatus(txt: String, colorHex: String = "#4ADE80") {
        if (::statusTextView.isInitialized) {
            statusTextView.text = if (txt.startsWith("الحالة:")) txt else "الحالة: $txt"
            statusTextView.setTextColor(Color.parseColor(colorHex))
        }
    }

    private fun copyText(label: String, valStr: String) {
        if (valStr.isBlank()) {
            toast("لا يوجد محتوى لنسخه: $label")
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, valStr))
        toast("تم نسخ $label إلى الحافظة.")
    }

    private fun createTopButton(l: String, act: () -> Unit) = Button(this).apply {
        text = l
        textSize = 11f
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = createRoundedDrawable("#162032", 8f)
        layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
        setOnClickListener { act() }
    }

    private fun createBottomButton(l: String, bg: String, act: () -> Unit) = Button(this).apply {
        text = l
        textSize = 11.5f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = createRoundedDrawable(bg, 8f)
        layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
        setOnClickListener { act() }
    }

    private fun createDialogLabel(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.parseColor("#93C5FD"))
        textSize = 12f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun createDialogInput(h: String, v: String) = EditText(this).apply {
        hint = h
        setText(v)
        setHintTextColor(Color.parseColor("#64748B"))
        setTextColor(Color.WHITE)
        textSize = 13f
        background = createRoundedDrawable("#1E293B", 8f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setTextIsSelectable(true)
    }

    private fun createRoundedDrawable(hex: String, r: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = r * resources.displayMetrics.density
        setColor(Color.parseColor(hex))
    }

    private fun createSpacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h))
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
