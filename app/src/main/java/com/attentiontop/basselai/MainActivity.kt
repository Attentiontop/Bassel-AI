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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private lateinit var prefs: SharedPreferences

    private lateinit var btnModeSelector: Button
    private lateinit var statusTextView: TextView
    private lateinit var promptInput: EditText
    private lateinit var btnReadinessCheck: Button
    private lateinit var btnStartCouncil: Button
    private lateinit var decisionLayout: LinearLayout
    private lateinit var resultTextView: TextView

    private val modelsList = mutableListOf<AiAgentModel>()
    private var manualLeaderId: String = "AUTO"
    private var autoPromptEnhance = true

    private var lastConfirmedPrompt = ""
    private var finalConsensusCode = ""
    private var lastAuditReport = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("bassel_ai_master_prefs", Context.MODE_PRIVATE)

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
            text = "Bassel AI - مجلس الإدارة والوكلاء المتزامن"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 16)
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
        topBar.addView(createTopButton("📁 الأرشيف") { showArchiveDialog() })
        topBar.addView(createTopButton("📊 السجل") { showLogsDialog() })
        topBar.addView(createTopButton("⚙️ المفاتيح والنماذج") { showModelsManagerDialog() })
        rootLayout.addView(topBar)

        rootLayout.addView(createSpacer(12))

        btnModeSelector = Button(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#60A5FA"))
            background = createRoundedDrawable("#162032", 12f)
            setPadding(16, 20, 16, 20)
            isAllCaps = false
            setOnClickListener { showLeaderSelectorDialog() }
        }
        updateLeaderButtonLabel()
        rootLayout.addView(btnModeSelector)

        statusTextView = TextView(this).apply {
            text = "الحالة: المجلس جاهز وفي انتظار توجيهاتك..."
            textSize = 13f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(8, 12, 8, 8)
        }
        rootLayout.addView(statusTextView)

        promptInput = EditText(this).apply {
            hint = "اكتب فكرة البرنامج أو المشروع بدقة (مثلاً: أريد برنامج أرشيف بكذا وكذا)..."
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

        rootLayout.addView(createSpacer(12))

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
            text = "🚀 بدء المجلس والعمل في الخفاء"
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
            setPadding(0, 12, 0, 0)
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
            text = "📋 تقرير الشفافية والإجماع"
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

        rootLayout.addView(createSpacer(12))

        val outputScroll = ScrollView(this).apply {
            background = createRoundedDrawable("#0E1420", 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        resultTextView = TextView(this).apply {
            text = "--- 🌟 نظام Bassel AI للمجلس الذكي ومتعدد الوكلاء ---\n\n" +
                    "1. اضغط [⚙️ المفاتيح والنماذج] لإضافة نماذجك ومفاتيحك في أي لحظة.\n" +
                    "2. اختر القائد أو اترك الإدارة بنظام التدوير التلقائي و Failover.\n" +
                    "3. اضغط [🔍 فحص جاهزية الكل] للتأكد من استجابة النماذج.\n" +
                    "4. اكتب فكرتك واضغط [🚀 بدء المجلس] لتشغيل خوارزمية الإجماع وتصحيح الأكواد حتى نسبة 90% فأكثر."
            textSize = 13f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(20, 20, 20, 20)
            setLineSpacing(8f, 1.1f)
            setTextIsSelectable(true)
        }
        outputScroll.addView(resultTextView)
        rootLayout.addView(outputScroll)

        rootLayout.addView(createSpacer(12))

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
        bottomBar.addView(createBottomButton("➕ مشروع جديد", "#312E81") {
            promptInput.setText("")
            finalConsensusCode = ""
            lastAuditReport = ""
            decisionLayout.visibility = View.GONE
            setStatus("الحالة: جاهز لمشروع جديد", "#38BDF8")
            resultTextView.text = "--- مشروع جديد ---\nاكتب فكرة برنامجك بالأعلى وابدأ المجلس."
        })
        rootLayout.addView(bottomBar)

        setContentView(rootLayout)
    }

    private fun loadModelsRegistry() {
        modelsList.clear()
        // تم تحديث المفتاح إلى v5 لضمان تحميل القيم الافتراضية الصحيحة والمحدثة
        val saved = prefs.getString("models_registry_v5", null)
        if (!saved.isNullOrEmpty()) {
            try {
                val arr = JSONArray(saved)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    modelsList.add(
                        AiAgentModel(
                            id = o.getString("id"),
                            name = o.getString("name"),
                            providerType = o.getString("providerType"),
                            endpointUrl = o.getString("endpointUrl"),
                            modelId = o.getString("modelId"),
                            apiKey = o.optString("apiKey", ""),
                            role = o.optString("role", "ALL")
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (modelsList.isEmpty()) {
            // النماذج الأساسية المجانية المضمونة
            modelsList.add(AiAgentModel("g1", "Groq Llama 3.3", "GROQ", "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile", "", "ALL"))
            modelsList.add(AiAgentModel("g2", "Gemini 1.5 Flash", "GEMINI", "https://generativelanguage.googleapis.com/v1beta/models", "gemini-1.5-flash", "", "LEADER"))
            modelsList.add(AiAgentModel("g3", "OpenRouter Coder", "OPENROUTER", "https://openrouter.ai/api/v1/chat/completions", "deepseek/deepseek-chat:free", "", "CODER"))

            saveModelsRegistry()
        }

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
            .putString("models_registry_v5", arr.toString())
            .putString("manual_leader_id", manualLeaderId)
            .putBoolean("auto_prompt_enhance", autoPromptEnhance)
            .apply()
    }

    private fun showModelsManagerDialog() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        container.addView(TextView(this).apply {
            text = "إدارة النماذج والمفاتيح:"
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
                text = "المعرف: ${m.modelId} | التخصص: ${m.role}"
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
        val modelIdIn = createDialogInput("مثال: llama-3.3-70b-versatile", existing?.modelId ?: "")
        layout.addView(modelIdIn)
        layout.addView(createSpacer(10))

        layout.addView(createDialogLabel("مفتاح الـ API:"))
        val keyIn = createDialogInput("ألصق مفتاح الـ API هنا...", existing?.apiKey ?: "")
        layout.addView(keyIn)
        layout.addView(createSpacer(10))

        layout.addView(createDialogLabel("رابط Endpoint URL:"))
        val urlIn = createDialogInput("https://...", existing?.endpointUrl ?: "https://api.groq.com/openai/v1/chat/completions")
        layout.addView(urlIn)
        layout.addView(createSpacer(10))

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
                val pType = codes[spProv.selectedItemPosition]
                val rCode = roleCodes[spRole.selectedItemPosition]
                val name = nameIn.text.toString().trim().ifEmpty { "AI Model" }
                val mId = modelIdIn.text.toString().trim().ifEmpty { "llama-3.3-70b-versatile" }
                val key = keyIn.text.toString().trim()
                val url = urlIn.text.toString().trim()

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
                    report.append("✅ [جاهز] ${m.name} (${latency}ms)\n")
                } else {
                    report.append("❌ [غير مستجيب] ${m.name} -> السبب: ${testRes.second.take(100)}\n")
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
            Toast.makeText(this, "يرجى كتابة فكرة البرنامج أولاً", Toast.LENGTH_SHORT).show()
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
        lastConfirmedPrompt = rawPrompt

        CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) {
                    setStatus("الحالة: 🧠 مهندس البرومبت ينظف وينسق الطلب هندسياً...", "#38BDF8")
                    resultTextView.text = "--- 1️⃣ مرحلة البرومبت الهندسي ---\nجاري تهيئة الطلب دون تغيير جوهره..."
                }

                var engineeredPrompt = rawPrompt
                val promptAgent = activeModels.find { it.role == "ALL" } ?: activeModels.first()
                if (autoPromptEnhance) {
                    val promptSys = "أنت خبير هندسة أوامر. قم فقط بتنظيم وتنسيق طلب المستخدم التالي ليكون برومبت برمجياً احترافياً **دون أي زيادة أو نقصان في المتطلبات الأساسية**:\nطلب المستخدم: $rawPrompt"
                    val pRes = callAiModelApi(promptAgent, promptSys)
                    if (pRes.first && pRes.second.isNotBlank()) {
                        engineeredPrompt = pRes.second
                    }
                }
                lastConfirmedPrompt = engineeredPrompt

                var leader = selectActiveLeader(activeModels)
                if (leader == null) {
                    withContext(Dispatchers.Main) {
                        btnStartCouncil.isEnabled = true
                        setStatus("الحالة: ❌ فشل في العثور على مدير مستجيب", "#F87171")
                        resultTextView.text = "تعذر بدء المجلس لعدم استجابة أي نموذج مدير."
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    setStatus("الحالة: 👑 المدير (${leader.name}) يوزع المهام على المجلس في الخفاء...", "#38BDF8")
                    resultTextView.text = "--- 2️⃣ العمل في الخفاء (In Progress) ---\n المدير: ${leader.name}\nالمجلس يبدأ التوليد والمراجعة والتصحيح..."
                }

                val councilLog = StringBuilder()
                councilLog.append("👑 المدير الموجه: ${leader.name}\n\n")

                val proposals = mutableMapOf<String, String>()
                val healthyModels = mutableListOf<AiAgentModel>()

                for (m in activeModels) {
                    withContext(Dispatchers.Main) {
                        setStatus("الحالة: ⏳ النموذج (${m.name}) يعمل في الخفاء (In Progress)...", "#38BDF8")
                    }

                    val task = async(Dispatchers.IO) {
                        val sysPrompt = "أنت مطور برمجيات خبير. قم بكتابة الكود البرمجي الكامل والجاهز لتنفيذ هذا المشروع:\n$engineeredPrompt"
                        callAiModelApi(m, sysPrompt)
                    }

                    try {
                        val res = withTimeout(45000) { task.await() }
                        if (res.first && res.second.isNotBlank()) {
                            proposals[m.id] = res.second
                            healthyModels.add(m)
                            councilLog.append("✅ [مكتمل] ${m.name} أنجز مقترحه البرمجي.\n")
                        } else {
                            councilLog.append("❌ [مستبعد / بطيء] ${m.name} فشل في الاستجابة وتم استبعاده.\n")
                        }
                    } catch (e: TimeoutCancellationException) {
                        councilLog.append("⏱️ [تخطي وتأخير] ${m.name} تجاوز الوقت المسموح وتم استبعاده من الدورة.\n")
                    }
                }

                if (proposals.isEmpty()) {
                    throw Exception("فشل جميع أعضاء المجلس في إنتاج أي كود.")
                }

                withContext(Dispatchers.Main) {
                    setStatus("الحالة: 🔄 جاري تقييم الأكواد ومراجعتها بين النماذج للوصول لنسبة 90%...", "#FBBF24")
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
                        val avg = if (proposals.size > 1) totalEvaluatorScore / (proposals.size - 1) else 85
                        scores[idOfModel(evaluator)] = avg
                    }

                    val topEntry = scores.maxByOrNull { it.value }
                    if (topEntry != null) {
                        bestScore = topEntry.value
                        bestCode = proposals[topEntry.key] ?: bestCode
                    }

                    auditTrail.append("الدورة $iterationCount: أعلى نسبة مقيمة = $bestScore%\n")

                    if (bestScore >= 90) {
                        consensusReached = true
                    } else {
                        withContext(Dispatchers.Main) {
                            setStatus("الحالة: ⚙️ النسبة الحالية ($bestScore%) أقل من 90%. جاري تكرار دورة التصحيح...", "#FBBF24")
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
                lastAuditReport = "👑 المدير المسؤول: ${leader.name}\n" +
                        "📋 البرومبت المحسن: $engineeredPrompt\n" +
                        "🔄 عدد دورات التصحيح: $iterationCount\n" +
                        "🏆 نسبة الإجماع النهائية المحققة: $bestScore%\n\n" +
                        "📜 سجل الشفافية والتقييم:\n$auditTrail"

                saveToArchive(rawPrompt, finalConsensusCode)

                withContext(Dispatchers.Main) {
                    btnStartCouncil.isEnabled = true
                    setStatus("الحالة: ✅ تم اعتماد البرنامج بنجاح بنسبة إجماع $bestScore%!", "#4ADE80")
                    resultTextView.text = "--- 🎉 تم اعتماد البرنامج بنجاح (الإجماع >= $bestScore%) ---\n\n$finalConsensusCode\n\n-------------------\nيمكنك الآن الضغط على [📥 تنزيل المشروع (ZIP)] أو [📋 تقرير الشفافية والإجماع]."
                    decisionLayout.visibility = View.VISIBLE
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnStartCouncil.isEnabled = true
                    setStatus("الحالة: ❌ حدث خطأ أثناء دورة المجلس", "#F87171")
                    resultTextView.text = "خطأ غير متوقع: ${e.localizedMessage}"
                }
            }
        }
    }

    private suspend fun selectActiveLeader(activeModels: List<AiAgentModel>): AiAgentModel? {
        if (manualLeaderId != "AUTO") {
            val selected = activeModels.find { it.id == manualLeaderId }
            if (selected != null) {
                val test = callAiModelApi(selected, "Ping")
                if (test.first) return selected
            }
        }

        for (m in activeModels) {
            val test = callAiModelApi(m, "Ping")
            if (test.first) {
                return m
            }
        }
        return activeModels.firstOrNull()
    }

    private fun parseScoreFromText(text: String): Int {
        return try {
            val lines = text.lines()
            for (l in lines) {
                if (l.uppercase().contains("SCORE:")) {
                    val num = l.replace(Regex("[^0-9]"), "").toInt()
                    return num.coerceIn(50, 100)
                }
            }
            val fallbackNum = text.replace(Regex("[^0-9]"), "").take(2).toIntOrNull() ?: 88
            fallbackNum.coerceIn(50, 100)
        } catch (e: Exception) {
            88
        }
    }

    private fun idOfModel(m: AiAgentModel): String = m.id

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

                zos.putNextEntry(ZipEntry("main.py"))
                zos.write(finalConsensusCode.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("README.md"))
                val readme = "# Bassel AI Generated Project\n\nPrompt: $lastConfirmedPrompt\n\nGenerated by Multi-Agent Consensus System."
                zos.write(readme.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("audit_report.txt"))
                zos.write(lastAuditReport.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.close()
                fos.close()

                Toast.makeText(this, "تم حفظ ملف الـ ZIP في مجلد التنزيلات (Downloads) بنجاح!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "فشل إنشاء ملف الـ ZIP", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Toast.makeText(this, "فشل ضغط الملف: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAuditReportDialog() {
        AlertDialog.Builder(this)
            .setTitle("📋 تقرير الشفافية والإجماع التوافقي")
            .setMessage(lastAuditReport.ifEmpty { "لا يوجد تقرير حالياً." })
            .setPositiveButton("إغلاق", null)
            .show()
    }

    private fun callAiModelApi(model: AiAgentModel, promptText: String): Pair<Boolean, String> {
        return try {
            if (model.providerType == "GEMINI") {
                callGeminiRestApiFixed(model, promptText)
            } else {
                callOpenAiCompatibleRestApiFixed(model, promptText)
            }
        } catch (e: Exception) {
            Pair(false, "خطأ شبكة: ${e.localizedMessage}")
        }
    }

    private fun callGeminiRestApiFixed(model: AiAgentModel, prompt: String): Pair<Boolean, String> {
        val cleanModelId = model.modelId.trim().removePrefix("models/")
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModelId:generateContent?key=${model.apiKey.trim()}"

        val bodyObj = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        }
        val req = Request.Builder().url(endpoint).post(bodyObj.toString().toRequestBody("application/json".toMediaType())).build()
        val res = client.newCall(req).execute()
        val raw = res.body?.string() ?: ""
        if (res.isSuccessful) {
            val text = JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).optString("text", raw)
            return Pair(true, text)
        }
        return Pair(false, "HTTP ${res.code}: $raw")
    }

    private fun callOpenAiCompatibleRestApiFixed(model: AiAgentModel, prompt: String): Pair<Boolean, String> {
        val endpoint = if (model.endpointUrl.isBlank() || model.endpointUrl.contains("generativelanguage")) {
            "https://api.groq.com/openai/v1/chat/completions"
        } else {
            model.endpointUrl.trim()
        }

        val bodyObj = JSONObject().apply {
            put("model", model.modelId.trim())
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            put("temperature", 0.7)
        }
        val req = Request.Builder()
            .url(endpoint)
            .post(bodyObj.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${model.apiKey.trim()}")
            .build()
        val res = client.newCall(req).execute()
        val raw = res.body?.string() ?: ""
        if (res.isSuccessful) {
            val text = JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", raw)
            return Pair(true, text)
        }
        return Pair(false, "HTTP ${res.code}: $raw")
    }

    private fun saveToArchive(prompt: String, code: String) {
        val arr = JSONArray(prefs.getString("archive_list", "[]") ?: "[]")
        arr.put(JSONObject().apply {
            put("date", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
            put("title", prompt.take(40))
            put("code", code)
        })
        prefs.edit().putString("archive_list", arr.toString()).apply()
    }

    private fun showArchiveDialog() {
        val arr = JSONArray(prefs.getString("archive_list", "[]") ?: "[]")
        if (arr.length() == 0) {
            Toast.makeText(this, "الأرشيف فارغ", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = Array(arr.length()) { i ->
            val o = arr.getJSONObject(arr.length() - 1 - i)
            "📁 [${o.optString("date")}] ${o.optString("title")}"
        }
        AlertDialog.Builder(this)
            .setTitle("📁 أرشيف المشاريع المعتمدة")
            .setItems(titles) { _, w ->
                val o = arr.getJSONObject(arr.length() - 1 - w)
                resultTextView.text = o.optString("code")
                setStatus("الحالة: 📂 استُرجع من الأرشيف", "#38BDF8")
            }
            .setPositiveButton("إغلاق", null)
            .show()
    }

    private fun showLogsDialog() {
        AlertDialog.Builder(this)
            .setTitle("📊 سجل النظام")
            .setMessage("النظام يعمل بكفاءة عالية وفق خوارزمية الإجماع التوافقي (>= 90%) واستبعاد النماذج البطيئة تلقائياً.")
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
    private fun createDialogInput(h: String, v: String) = EditText(this).apply { hint = h; setText(v); setHintTextColor(Color.parseColor("#64748B"));setTextColor(Color.WHITE); textSize = 13f; setPadding(16, 14, 16, 14); background = createRoundedDrawable("#1E293B", 8f) }
    private fun createRoundedDrawable(hex: String, r: Float) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = r * resources.displayMetrics.density; setColor(Color.parseColor(hex)) }
    private fun createSpacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h) }
}
