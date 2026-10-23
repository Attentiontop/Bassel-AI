package com.attentiontop.basselai

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * Bassel AI — single-Activity, programmatic UI starter.
 *
 * Required app setup:
 * - minSdk 23+; INTERNET permission.
 * - Kotlin + kotlinx-coroutines-android dependency.
 * - AndroidX is not required by this file.
 *
 * API keys are encrypted using an Android Keystore AES-GCM key. Provider requests are
 * performed off the main thread. The UI and workflow are deliberately bounded: at most
 * 4 refinement rounds and 90 seconds per model request. Model self-reported confidence
 * is a heuristic, not proof of correctness or objective consensus.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences("bassel_ai", MODE_PRIVATE) }
    private val models = mutableListOf<AiModel>()
    private val modelStates = linkedMapOf<String, String>()
    private val audit = mutableListOf<String>()
    private val proposalArchive = mutableListOf<Pair<String, String>>()
    private val reviewArchive = mutableListOf<Pair<String, String>>()
    private var workflowJob: Job? = null
    private var latestOutput: String = ""
    private var latestZip: java.io.File? = null

    private lateinit var root: LinearLayout
    private lateinit var modelList: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var promptInput: EditText
    private lateinit var outputText: EditText
    private lateinit var leaderSpinner: Spinner
    private lateinit var roleSpinner: Spinner
    private lateinit var refinedPrompt: EditText
    private lateinit var auditText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var agentStatusText: TextView
    private var lastTaskPrompt: String = ""
    private var lastTaskStatus: String = "لم يبدأ التنفيذ"
    private var lastReviewScore: Int? = null

    private val bg = Color.rgb(17, 22, 33)
    private val panel = Color.rgb(29, 37, 53)
    private val accent = Color.rgb(95, 155, 255)
    private val foreground = Color.rgb(236, 241, 250)
    private val muted = Color.rgb(170, 183, 204)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        loadModels()
        buildUi()
        restoreSession()
        refreshModels()
        refreshLeaderSpinner()
        setStatus("جاهز — أضف نموذجًا ثم نفّذ فحص الجاهزية.")
    }

    override fun onDestroy() {
        workflowJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            setBackgroundColor(bg)
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(label("BASSEL AI", 26, foreground, true))
        root.addView(label("مجلس نماذج الذكاء الاصطناعي • محلي على الهاتف", 13, muted))
        root.addView(space(12))
        root.addView(sectionTitle("١. سجل النماذج"))
        modelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(modelList)
        rowButtons(
            button("＋ إضافة نموذج") { showModelEditor(null) },
            button("فحص الجاهزية") { runPreflight() }
        )
        root.addView(space(12))
        root.addView(sectionTitle("٢. المهمة"))
        promptInput = edit("اكتب الطلب الأصلي هنا…", 4)
        root.addView(promptInput)
        root.addView(button("صياغة الطلب بواسطة Prompt Engineer") { refinePrompt() })
        refinedPrompt = edit("ستظهر الصياغة المقترحة هنا للمراجعة…", 5)
        root.addView(refinedPrompt)
        root.addView(button("اعتماد الصياغة واستخدامها") {
            if (refinedPrompt.text.toString().isNotBlank()) {
                promptInput.setText(refinedPrompt.text.toString())
                setStatus("تم اعتماد الصياغة. راجع الطلب قبل بدء المجلس.")
            } else toast("لا توجد صياغة لاعتمادها.")
        })
        root.addView(space(8))
        root.addView(sectionTitle("٣. القائد والتنفيذ"))
        leaderSpinner = Spinner(this)
        root.addView(leaderSpinner)
        root.addView(button("بدء المجلس") { startCouncil() })
        progressBar = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progressBar)
        statusText = label("الحالة: جاهز", 14, accent, true)
        root.addView(statusText)
        root.addView(sectionTitle("حالة الوكلاء"))
        root.addView(label("ملخص حي للخطوات الفعلية؛ لا يُعرض أي نموذج على أنه يعمل ما لم يكن الطلب قد أُرسل إليه فعلًا.", 12, muted))
        agentStatusText = label("لم يبدأ التنفيذ.", 13, foreground)
        agentStatusText.setTextIsSelectable(true)
        root.addView(agentStatusText)
        root.addView(space(8))
        root.addView(sectionTitle("٤. الحل النهائي"))
        outputText = edit("سيظهر الحل النهائي هنا…", 10).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(outputText)
        rowButtons(
            button("إنشاء Project.zip") { createZip() },
            button("تصدير ZIP") { exportZip() }
        )
        rowButtons(
            button("نسخ الكود النهائي") { copyText("الكود النهائي", outputText.text.toString()) },
            button("نسخ التقرير") { copyText("تقرير الشفافية", buildFullAuditReport()) }
        )
        rowButtons(
            button("نسخ ذاكرة JSON") { copyText("ذاكرة المهمة", buildTaskMemoryJson().toString(2)) },
            button("نسخ سجل الحالات") { copyText("سجل الحالات", buildStatusSummary()) }
        )
        root.addView(sectionTitle("٥. تقرير التدقيق والشفافية"))
        auditText = label("لا يوجد تنفيذ بعد.", 12, muted)
        auditText.setTextIsSelectable(true)
        root.addView(auditText)
    }

    private fun sectionTitle(s: String) = label(s, 17, foreground, true).apply {
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun label(s: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(2), dp(5), dp(2), dp(5))
        }

    private fun edit(hintText: String, lines: Int) = EditText(this).apply {
        hint = hintText
        setHintTextColor(Color.rgb(125, 139, 160))
        setTextColor(this@MainActivity.foreground)
        textSize = 14f
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundColor(panel)
        minLines = lines
        gravity = Gravity.TOP
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        layoutParams = matchWidth()
    }

    // زر مستقل داخل التخطيط العمودي: يجب أن يكون عرضه ممتدًا، لا صفرًا.
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48)
        ).apply {
            setMargins(dp(3), dp(4), dp(3), dp(4))
        }
    }

    // أزرار الصف الأفقي تُقسم المساحة بالتساوي بدل عرض MATCH_PARENT.
    private fun rowButtons(a: Button, b: Button) {
        val paramsA = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(dp(3), dp(4), dp(3), dp(4))
        }
        val paramsB = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(dp(3), dp(4), dp(3), dp(4))
        }
        a.layoutParams = paramsA
        b.layoutParams = paramsB
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = matchWidth()
            addView(a)
            addView(b)
        })
    }

    private fun space(n: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(n))
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, dp(4), 0, dp(4)) }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun setStatus(s: String) {
        if (::statusText.isInitialized) statusText.text = "الحالة: $s"
        lastTaskStatus = s
        if (::agentStatusText.isInitialized) refreshAgentTimeline()
        persistSession()
    }

    private fun refreshModels() {
        val selectedLeaderId = if (::leaderSpinner.isInitialized) models.getOrNull(leaderSpinner.selectedItemPosition)?.id else null
        modelList.removeAllViews()
        if (models.isEmpty()) {
            modelList.addView(label("لا توجد نماذج مضبوطة.", 13, muted))
        }
        models.forEach { model ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(panel)
                layoutParams = matchWidth()
            }
            card.addView(label("${model.name} • ${model.provider} • ${model.role}", 14, foreground, true))
            card.addView(label("${model.modelId}\n${model.endpoint}", 11, muted))
            card.addView(label(modelStates[model.id] ?: "لم يُفحص", 12, accent))
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = matchWidth()
            }
            val editButton = button("تعديل") { showModelEditor(model) }
            editButton.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(dp(3), dp(4), dp(3), dp(4))
            }
            actions.addView(editButton)
            val deleteButton = button("حذف") {
                AlertDialog.Builder(this).setTitle("حذف النموذج؟")
                    .setMessage("سيُحذف ${model.name} من السجل المحلي.")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("حذف") { _, _ ->
                        models.removeAll { it.id == model.id }
                        modelStates.remove(model.id)
                        saveModels(); refreshModels(); refreshLeaderSpinner()
                    }.show()
            }
            deleteButton.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(dp(3), dp(4), dp(3), dp(4))
            }
            actions.addView(deleteButton)
            card.addView(actions)
            modelList.addView(card)
        }
        refreshLeaderSpinner(selectedLeaderId)
        if (::agentStatusText.isInitialized) refreshAgentTimeline()
    }

    private fun refreshLeaderSpinner(preferredId: String? = null) {
        if (!::leaderSpinner.isInitialized) return
        val names = models.map { "${it.name} (${it.modelId})" }
        leaderSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val preferredIndex = models.indexOfFirst { it.id == preferredId }
        if (preferredIndex >= 0) leaderSpinner.setSelection(preferredIndex)
        leaderSpinner.isEnabled = names.isNotEmpty()
    }

    private fun showModelEditor(existing: AiModel?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), 0)
        }
        fun field(hint: String, value: String = "", secret: Boolean = false): EditText {
            return EditText(this).apply {
                this.hint = hint; setText(value); setTextColor(this@MainActivity.foreground); setHintTextColor(this@MainActivity.muted)
                if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true); setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(panel); box.addView(this)
            }
        }
        val name = field("اسم النموذج", existing?.name.orEmpty())
        val provider = Spinner(this)
        provider.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("OpenAI-compatible", "Gemini", "Groq", "OpenRouter"))
        val providers = listOf("OpenAI-compatible", "Gemini", "Groq", "OpenRouter")
        if (existing != null) provider.setSelection(providers.indexOf(existing.provider).coerceAtLeast(0))
        box.addView(provider)
        val endpoint = field("Endpoint URL", existing?.endpoint ?: "https://api.openai.com/v1/chat/completions")
        val modelId = field("Model ID", existing?.modelId.orEmpty())
        var lastProviderPosition = provider.selectedItemPosition
        provider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == lastProviderPosition) return
                lastProviderPosition = position
                if (existing != null) return
                when (providers.getOrNull(position)) {
                    "Gemini" -> {
                        endpoint.setText("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent")
                        modelId.setText("gemini-3.8-flash")
                    }
                    "Groq" -> {
                        endpoint.setText("https://api.groq.com/openai/v1/chat/completions")
                        modelId.setText("openai/gpt-oss-120b")
                    }
                    "OpenRouter" -> {
                        endpoint.setText("https://openrouter.ai/api/v1/chat/completions")
                        modelId.setText("openrouter/free")
                    }
                    else -> {
                        endpoint.setText("https://api.openai.com/v1/chat/completions")
                        modelId.setText("")
                    }
                }
            }
        }
        val key = field("API Key (اتركه فارغًا للإبقاء على المفتاح الحالي)", "", true)
        val roles = Spinner(this)
        roles.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Prompt Engineer", "Planner/Leader", "Coder", "Reviewer"))
        if (existing != null) roles.setSelection(listOf("Prompt Engineer", "Planner/Leader", "Coder", "Reviewer").indexOf(existing.role).coerceAtLeast(0))
        box.addView(roles)
        AlertDialog.Builder(this).setTitle(if (existing == null) "إضافة نموذج" else "تعديل نموذج")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ") { _, _ ->
                val n = name.text.toString().trim()
                val ep = endpoint.text.toString().trim()
                val mid = modelId.text.toString().trim()
                if (n.isBlank() || ep.isBlank() || mid.isBlank() ||
                    !(ep.startsWith("https://") || ep.startsWith("http://"))) {
                    toast("أدخل الاسم وEndpoint صالحًا وModel ID.")
                    return@setPositiveButton
                }
                val id = existing?.id ?: UUID.randomUUID().toString()
                // Blank key input means preserve the exact encrypted bytes already stored.
                // This avoids losing a key if decryption temporarily fails or the Keystore changes.
                val enteredKey = key.text.toString()
                val encryptedSecret = if (enteredKey.isNotBlank()) encryptKey(enteredKey)
                    else existing?.encryptedKey.orEmpty()
                val m = AiModel(id, n, providers[provider.selectedItemPosition], ep, mid,
                    encryptedSecret, roles.selectedItem.toString())
                val index = models.indexOfFirst { it.id == id }
                if (index >= 0) models[index] = m else models.add(m)
                saveModels(); refreshModels()
            }.show()
    }

    private fun refinePrompt() {
        val raw = promptInput.text.toString().trim()
        if (raw.isBlank()) { toast("اكتب الطلب أولًا."); return }
        val agent = models.firstOrNull { it.role == "Prompt Engineer" } ?: models.firstOrNull()
        if (agent == null) { toast("أضف نموذجًا أولًا."); return }
        scope.launch {
            setBusy(true, "صياغة الطلب…")
            try {
                val response = withTimeout(90_000) {
                    request(agent, "أعد صياغة الطلب التالي بشكل واضح ومنظم دون تغيير قصده أو إضافة متطلبات جديدة. أخرج الصياغة فقط:\n\n$raw")
                }
                refinedPrompt.setText(response)
                setStatus("اكتملت الصياغة. راجعها ثم اعتمدها يدويًا.")
            } catch (e: Exception) {
                setStatus("فشلت الصياغة: ${safeError(e)}")
            } finally { setBusy(false) }
        }
    }

    private fun runPreflight() {
        if (models.isEmpty()) { toast("أضف نموذجًا أولًا."); return }
        scope.launch {
            setBusy(true, "فحص جميع النماذج بالتوازي…")
            val results = models.map { m ->
                async(Dispatchers.IO) {
                    val start = System.currentTimeMillis()
                    try {
                        withTimeout(25_000) {
                            request(m, "Reply with exactly: READY")
                        }
                        Triple(m.id, "جاهز • ${System.currentTimeMillis() - start} ms", true)
                    } catch (e: Exception) {
                        Triple(m.id, "فشل • ${safeError(e)}", false)
                    }
                }
            }.awaitAll()
            results.forEach { (id, state, _) -> modelStates[id] = state }
            refreshModels()
            setStatus("انتهى الفحص: ${results.count { it.third }} من ${results.size} استجابوا.")
            audit.add("Preflight: " + results.joinToString(" | ") { "${modelName(it.first)}=${it.second}" })
            refreshAudit()
            setBusy(false)
        }
    }

    private fun startCouncil() {
        if (workflowJob?.isActive == true) { toast("هناك تنفيذ جارٍ بالفعل."); return }
        val prompt = promptInput.text.toString().trim()
        if (prompt.isBlank()) { toast("اكتب المهمة أولًا."); return }
        val active = models.filter { decryptKey(it.encryptedKey).isNotBlank() }
        if (active.isEmpty()) { toast("أضف نماذج مع مفاتيح API صالحة."); return }
        val selectedLeader = models.getOrNull(leaderSpinner.selectedItemPosition)
        audit.clear()
        modelStates.clear()
        proposalArchive.clear()
        reviewArchive.clear()
        lastTaskPrompt = prompt
        lastReviewScore = null
        lastTaskStatus = "بدأ التنفيذ"
        persistSession()
        workflowJob = scope.launch {
            setBusy(true, "المرحلة 1/5: فحص اتصال النماذج…")
            try {
                audit.add("بدء المهمة: ${java.util.Date()}")
                appendAudit("تم حفظ الطلب في سجل المهمة. المفاتيح لا تُدرج في السجل أو التقرير.")
                val preflight = active.map { m ->
                    modelStates[m.id] = "بانتظار فحص الاتصال"
                    refreshModels()
                    async(Dispatchers.IO) {
                        try { withTimeout(25_000) { request(m, "Reply with exactly: READY") }; Triple(m, true, null as String?) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Triple(m, false, safeError(e)) }
                    }
                }.awaitAll()
                var healthy = preflight.filter { it.second }.map { it.first }
                preflight.forEach { (m, ok, err) ->
                    modelStates[m.id] = if (ok) "جاهز • اجتاز فحص الاتصال" else "فشل الاتصال • ${err ?: "غير محدد"}"
                    appendAudit("فحص ${m.name}: ${if (ok) "نجح" else "فشل: ${err ?: "غير محدد"}"}")
                }
                if (healthy.isEmpty()) throw IllegalStateException("لم يستجب أي نموذج لفحص الجاهزية.")
                var leader = selectedLeader?.takeIf { chosen -> healthy.any { it.id == chosen.id } } ?: healthy.first()
                appendAudit("القائد المنسق: ${leader.name}")

                setStatus("المرحلة 2/5: ${leader.name} يضع خطة موجزة للمهمة…")
                modelStates[leader.id] = "يضع خطة المهمة"
                refreshModels()
                val plan = try {
                    withTimeout(90_000) {
                        request(leader, "حلّل المهمة واكتب خطة تنفيذ موجزة ومعايير قبول واضحة. لا تدّع تنفيذ أي شيء. المهمة:\n$prompt")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val replacement = healthy.firstOrNull { it.id != leader.id } ?: throw e
                    modelStates[leader.id] = "فشل إعداد الخطة • ${safeError(e)}"
                    appendAudit("تعذر على ${leader.name} إعداد الخطة؛ جرت محاولة بديلة بواسطة ${replacement.name}.")
                    leader = replacement
                    modelStates[leader.id] = "يعيد محاولة إعداد الخطة"
                    refreshModels()
                    withTimeout(90_000) {
                        request(leader, "حلّل المهمة واكتب خطة تنفيذ موجزة ومعايير قبول واضحة. لا تدّع تنفيذ أي شيء. المهمة:\n$prompt")
                    }
                }
                appendAudit("خطة ${leader.name}:\n${plan.take(2500)}")

                setStatus("المرحلة 3/5: النماذج النشطة تولّد مقترحات مستقلة…")
                val proposalResults = healthy.mapIndexed { index, model ->
                    modelStates[model.id] = "ينشئ المقترح ${index + 1}/${healthy.size}"
                    refreshModels()
                    async(Dispatchers.IO) {
                        try {
                            val proposal = withTimeout(90_000) {
                                request(model, """
                                    أنت وكيل برمجي مستقل ضمن Bassel AI.
                                    نفّذ المهمة مباشرةً وفق الخطة، وأخرج الحل الكامل القابل للمراجعة.
                                    إذا كانت المهمة برمجية، أخرج الملفات في كتل fenced code مع اسم كل ملف.
                                    لا تدّع تشغيل الكود أو الاختبارات؛ أنت تولّد مقترحًا فقط.
                                    المهمة:
                                    $prompt
                                    خطة المنسق:
                                    $plan
                                """.trimIndent())
                            }
                            Proposal(model, proposal, null)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Proposal(model, "", safeError(e)) }
                    }
                }.awaitAll()
                var proposals = proposalResults.filter { it.error == null && it.text.isNotBlank() }
                proposalResults.forEach { p ->
                    modelStates[p.model.id] = if (p.error == null) "اكتمل المقترح الأولي" else "فشل توليد المقترح • ${p.error}"
                    appendAudit("توليد ${p.model.name}: ${if (p.error == null) "نجح (${p.text.length} حرفًا)" else "فشل: ${p.error}"}")
                }
                if (proposals.isEmpty()) throw IllegalStateException("لم يُنتج أي نموذج مقترحًا صالحًا.")
                proposalArchive.clear()
                proposals.forEach { proposal -> proposalArchive.add(proposal.model.name to proposal.text.take(12000)) }

                setStatus("المرحلة 4/5: مراجعة مستقلة للمقترحات ومقارنة النتائج…")
                val reviewerResults = healthy.map { reviewer ->
                    modelStates[reviewer.id] = "يراجع جميع المقترحات"
                    refreshModels()
                    async(Dispatchers.IO) {
                        try {
                            val comparisonPrompt = buildString {
                                append("أنت مراجع مستقل. قيّم كل مقترح مقابل المهمة والخطة. لا تقيّم نفسك بشكل تفضيلي. ")
                                append("أخرج لكل مقترح سطرًا بالضبط بالشكل CANDIDATE_<رقم>_SCORE: <عدد من 0 إلى 100>. ")
                                append("بعد الأسطر اشرح العيوب الجوهرية لكل مقترح باختصار، ولا تدّع تشغيل اختبارات.\n\n")
                                append("المهمة:\n$prompt\n\nالخطة:\n$plan\n\n")
                                proposals.forEachIndexed { i, proposal ->
                                    append("=== CANDIDATE_${i + 1} من ${proposal.model.name} ===\n")
                                    append(proposal.text.take(14000)).append("\n\n")
                                }
                            }
                            val response = withTimeout(90_000) { request(reviewer, comparisonPrompt) }
                            ReviewerReport(reviewer, response, null)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { ReviewerReport(reviewer, "", safeError(e)) }
                    }
                }.awaitAll()
                reviewArchive.clear()
                reviewerResults.forEach { r ->
                    modelStates[r.model.id] = if (r.error == null) "اكتملت المراجعة المستقلة" else "فشلت المراجعة • ${r.error}"
                    if (r.error == null) reviewArchive.add(r.model.name to r.text.take(7000))
                    appendAudit("مراجعة ${r.model.name}: ${if (r.error == null) r.text.take(2200) else "فشل: ${r.error}"}")
                }
                val validReviews = reviewerResults.filter { it.error == null }
                if (validReviews.isEmpty()) throw IllegalStateException("لم تنجح أي مراجعة مستقلة؛ لن نعلن إجماعًا.")
                val scored = proposals.mapIndexed { index, proposal ->
                    val candidateNo = index + 1
                    val scores = validReviews.mapNotNull { review -> parseCandidateScore(review.text, candidateNo) }
                    CandidateScore(proposal, scores, if (scores.isNotEmpty()) scores.average() else null)
                }
                scored.forEach { item ->
                    appendAudit("تقييم ${item.proposal.model.name}: ${item.scores.size}/${validReviews.size} مراجعات صالحة؛ المتوسط=${item.average?.let { "%.1f".format(java.util.Locale.US, it) } ?: "لا توجد درجة قابلة للاستخراج"}.")
                }
                val minimumReviewCount = maxOf(1, (validReviews.size + 1) / 2)
                val winner = scored.filter { it.average != null && it.scores.size >= minimumReviewCount }
                    .maxByOrNull { it.average!! }
                    ?: throw IllegalStateException("لم يحصل أي مقترح على درجات صالحة من نصف المراجعين على الأقل؛ لم يُختر فائز اعتباطيًا.")
                var candidate = winner.proposal.text
                val selectedAverage = winner.average!!.toInt().coerceIn(0, 100)
                lastReviewScore = selectedAverage
                appendAudit("المقترح المختار مبدئيًا: ${winner.proposal.model.name}، متوسط درجات المراجعين=${selectedAverage}/100. هذه درجة تقييم نصي وليست نسبة صحة أو إجماع.")

                setStatus("المرحلة 5/5: ${leader.name} يدمج الملاحظات في نسخة نهائية…")
                modelStates[leader.id] = "يدمج المراجعات في النسخة النهائية"
                refreshModels()
                val reviewDigest = validReviews.joinToString("\n\n") { "مراجعة ${it.model.name}:\n${it.text.take(3500)}" }
                candidate = withTimeout(90_000) {
                    request(leader, """
                        أنت المنسق النهائي. حسّن المقترح المختار باستخدام المراجعات المستقلة.
                        أخرج الحل الكامل فقط قدر الإمكان. لا تخترع نتائج اختبارات ولا تقل إنك شغّلت الكود.
                        المهمة:
                        $prompt
                        الخطة:
                        $plan
                        المقترح المختار من ${winner.proposal.model.name}:
                        ${candidate.take(18000)}
                        مراجعات المستقلة:
                        $reviewDigest
                    """.trimIndent())
                }
                latestOutput = candidate
                outputText.setText(candidate)
                modelStates[leader.id] = "أكمل دمج المراجعات"
                healthy.filter { it.id != leader.id }.forEach { modelStates[it.id] = "اكتملت المشاركة؛ راجع السجل للتفاصيل" }
                appendAudit("لم يُشغّل التطبيق الكود الناتج ولم ينفّذ اختبارات برمجية فعلية. لا يوجد مفسّر Python أو بيئة تنفيذ معزولة مدمجة في هذه النسخة.")
                lastTaskStatus = "اكتمل التوليد والمراجعة النصية؛ الاختبارات الفعلية لم تُشغّل"
                appendAudit("الحالة النهائية: $lastTaskStatus")
                setStatus("اكتمل التوليد والمراجعة. درجة المراجعة النصية ${selectedAverage}/100؛ الاختبارات الفعلية لم تُشغّل.")
            } catch (e: CancellationException) {
                lastTaskStatus = "أُلغي التنفيذ"
                appendAudit("أُلغي التنفيذ بواسطة المستخدم أو دورة حياة التطبيق.")
                setStatus("أُلغي التنفيذ.")
                throw e
            } catch (e: Exception) {
                lastTaskStatus = "فشل: ${safeError(e)}"
                appendAudit("خطأ نهائي: ${safeError(e)}")
                setStatus("توقف التنفيذ: ${safeError(e)}")
            } finally {
                persistSession()
                refreshAudit()
                refreshModels()
                setBusy(false)
            }
        }
    }

    private data class Proposal(val model: AiModel, val text: String, val error: String?)
    private data class ReviewerReport(val model: AiModel, val text: String, val error: String?)
    private data class CandidateScore(val proposal: Proposal, val scores: List<Int>, val average: Double?)

    private fun parseCandidateScore(text: String, candidateNumber: Int): Int? {
        val regex = Regex("(?im)^\\s*CANDIDATE_${candidateNumber}_SCORE\\s*:\\s*(100|[1-9]?\\d)\\s*$")
        return regex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..100 }
    }

    private suspend fun request(model: AiModel, prompt: String): String = withContext(Dispatchers.IO) {
        val key = decryptKey(model.encryptedKey)
        require(key.isNotBlank()) { "مفتاح API غير مضبوط" }
        val endpoint = if (model.provider == "Gemini" && !model.endpoint.contains(":generateContent")) {
            model.endpoint.trimEnd('/') + "/models/${model.modelId}:generateContent"
        } else model.endpoint
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 85_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (model.provider == "Gemini") {
                setRequestProperty("x-goog-api-key", key)
            } else {
                setRequestProperty("Authorization", "Bearer $key")
                if (model.provider == "OpenRouter") {
                    setRequestProperty("HTTP-Referer", "https://localhost")
                    setRequestProperty("X-Title", "Bassel AI")
                }
            }
        }
        try {
            val body = if (model.provider == "Gemini") {
                JSONObject().put("contents", JSONArray().put(JSONObject().put("parts",
                    JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", JSONObject().put("temperature", 0.2))
            } else {
                JSONObject().put("model", model.modelId)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", "You are a careful AI council agent. Be explicit about uncertainty."))
                        .put(JSONObject().put("role", "user").put("content", prompt)))
                    .put("temperature", 0.2)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                val message = try { JSONObject(text).optJSONObject("error")?.optString("message") ?: text.take(400) }
                    catch (_: Exception) { text.take(400) }
                throw IllegalStateException("HTTP $code: $message")
            }
            val json = JSONObject(text)
            if (model.provider == "Gemini") {
                json.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                    ?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("استجابة Gemini لا تحتوي نصًا.")
            } else {
                json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                    ?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("الاستجابة لا تحتوي choices[0].message.content.")
            }
        } finally { conn.disconnect() }
    }

    private fun createZip() {
        val source = outputText.text.toString().trim()
        if (source.isBlank()) { toast("لا يوجد حل لتعبئته."); return }
        scope.launch {
            setBusy(true, "إنشاء Project.zip…")
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = getExternalFilesDir(null) ?: filesDir
                    val zip = java.io.File(dir, "Project.zip")
                    ZipOutputStream(zip.outputStream().buffered()).use { zos ->
                        val files = splitGeneratedFiles(source)
                        files.forEach { (name, content) ->
                            val safeName = name.replace("\\", "/").split("/")
                                .filter { it.isNotBlank() && it != "." && it != ".." }.joinToString("/")
                                .ifBlank { "output.txt" }
                            zos.putNextEntry(ZipEntry(safeName))
                            zos.write(content.toByteArray(StandardCharsets.UTF_8))
                            zos.closeEntry()
                        }
                        val report = buildString {
                            append(audit.joinToString("\n"))
                            append("\n\nالحالة: ").append(lastTaskStatus)
                            append("\nالاختبارات البرمجية الفعلية: لم تُشغّل داخل هذا التطبيق.")
                            append("\nدرجة المراجعة النصية لا تثبت صحة الكود.")
                        }
                        zos.putNextEntry(ZipEntry("AUDIT_REPORT.txt"))
                        zos.write(report.toByteArray(StandardCharsets.UTF_8))
                        zos.closeEntry()
                        zos.putNextEntry(ZipEntry("TASK_MEMORY.json"))
                        zos.write(buildTaskMemoryJson().toString(2).toByteArray(StandardCharsets.UTF_8))
                        zos.closeEntry()
                    }
                    zip
                }
                latestZip = file
                prefs.edit().putString("latest_zip_path_v1", file.absolutePath).apply()
                audit.add("تم إنشاء ZIP: ${file.absolutePath} (${file.length()} bytes)")
                refreshAudit()
                setStatus("تم إنشاء Project.zip (${file.length()} bytes).")
                toast("تم إنشاء ZIP داخل مساحة التطبيق.")
            } catch (e: Exception) {
                setStatus("فشل إنشاء ZIP: ${safeError(e)}")
            } finally { setBusy(false) }
        }
    }

    /**
     * Accepts explicit ```filename.ext fenced blocks. If none are found, stores the whole
     * response as README_or_output.md. This is intentionally conservative: it does not
     * pretend to infer a reliable project tree from arbitrary prose.
     */
    private fun splitGeneratedFiles(text: String): List<Pair<String, String>> {
        val regex = Regex("(?s)```([^\\n`]*)\\n(.*?)```")
        val matches = regex.findAll(text).toList()
        if (matches.isEmpty()) return listOf("README_or_output.md" to text)
        val result = mutableListOf<Pair<String, String>>()
        matches.forEachIndexed { index, match ->
            val header = match.groupValues[1].trim()
            val path = Regex("[A-Za-z0-9_./-]+\\.[A-Za-z0-9]+").find(header)?.value
                ?: "generated_file_${index + 1}.txt"
            result.add(path to match.groupValues[2].trimEnd())
        }
        return result
    }

    private fun exportZip() {
        val zip = latestZip
        if (zip == null || !zip.exists() || zip.length() <= 0L) {
            toast("أنشئ ZIP أولًا؛ لم يُعثر على ملف صالح للتصدير.")
            return
        }
        scope.launch {
            setBusy(true, "حفظ ZIP في Downloads…")
            var insertedUri: Uri? = null
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    withContext(Dispatchers.IO) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "BasselAI_Project.zip")
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw IllegalStateException("تعذر إنشاء ملف في Downloads.")
                        insertedUri = uri
                        try {
                            contentResolver.openOutputStream(uri, "w")?.use { out -> zip.inputStream().use { it.copyTo(out) } }
                                ?: throw IllegalStateException("تعذر فتح ملف Downloads للكتابة.")
                            val ready = android.content.ContentValues().apply {
                                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                            }
                            contentResolver.update(uri, ready, null, null)
                        } catch (e: Exception) {
                            contentResolver.delete(uri, null, null)
                            throw e
                        }
                    }
                    appendAudit("تم تصدير ZIP إلى مجلد Downloads عبر MediaStore.")
                    setStatus("تم حفظ BasselAI_Project.zip في Downloads.")
                    toast("تم حفظ ZIP في Downloads.")
                } else {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/zip"
                        putExtra(Intent.EXTRA_TITLE, "BasselAI_Project.zip")
                    }
                    startActivityForResult(intent, REQUEST_EXPORT)
                    setStatus("اختر مجلد Downloads في نافذة الحفظ.")
                }
            } catch (e: Exception) {
                insertedUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
                appendAudit("فشل تصدير ZIP: ${safeError(e)}")
                setStatus("فشل تصدير ZIP: ${safeError(e)}")
                toast("فشل التصدير: ${safeError(e)}")
            } finally { setBusy(false) }
        }
    }

    @Deprecated("Uses the compatible Activity result callback for broad Android support")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val zip = latestZip ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { out -> zip.inputStream().use { it.copyTo(out) } }
                        ?: throw IllegalStateException("تعذر فتح وجهة التصدير.")
                }
                toast("تم تصدير Project.zip.")
            } catch (e: Exception) { toast("فشل التصدير: ${safeError(e)}") }
        }
    }

    private fun refreshAudit() {
        if (::auditText.isInitialized) auditText.text = audit.takeLast(160).joinToString("\n")
        if (::agentStatusText.isInitialized) refreshAgentTimeline()
        persistSession()
    }

    private fun refreshAgentTimeline() {
        if (!::agentStatusText.isInitialized) return
        val lines = models.map { model ->
            "• ${model.name} — ${modelStates[model.id] ?: "لم يبدأ دوره"}"
        }
        agentStatusText.text = (listOf("حالة المهمة: $lastTaskStatus") + lines).joinToString("\n")
    }

    private fun appendAudit(message: String) {
        audit.add("[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}] $message")
        refreshAudit()
    }

    private fun buildStatusSummary(): String = buildString {
        append("حالة المهمة: ").append(lastTaskStatus).append("\n")
        models.forEach { append(it.name).append(" — ").append(modelStates[it.id] ?: "لم يبدأ دوره").append("\n") }
    }

    private fun buildFullAuditReport(): String = buildString {
        append(buildStatusSummary()).append("\n")
        append("نتيجة المراجعة النصية: ").append(lastReviewScore?.let { "$it/100" } ?: "غير متاحة").append("\n")
        append("الاختبارات الفعلية: لم تُشغّل داخل التطبيق.\n\n")
        append(audit.joinToString("\n"))
    }

    private fun buildTaskMemoryJson(): JSONObject = JSONObject()
        .put("schema_version", 1)
        .put("task_prompt", lastTaskPrompt)
        .put("task_status", lastTaskStatus)
        .put("created_or_updated_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).format(java.util.Date()))
        .put("final_output", outputTextOrLatest())
        .put("review_score_out_of_100", lastReviewScore ?: JSONObject.NULL)
        .put("review_score_meaning", "متوسط درجات المراجعة النصية، وليس نسبة صحة أو إجماع")
        .put("tests_executed", false)
        .put("audit_log", JSONArray().apply { audit.forEach { put(it) } })
        .put("proposals", JSONArray().apply {
            proposalArchive.forEach { (name, content) -> put(JSONObject().put("model", name).put("content_excerpt", content)) }
        })
        .put("reviews", JSONArray().apply {
            reviewArchive.forEach { (name, content) -> put(JSONObject().put("model", name).put("review_excerpt", content)) }
        })
        .put("model_states", JSONObject().apply { models.forEach { put(it.name, modelStates[it.id] ?: "لم يبدأ دوره") } })

    private fun outputTextOrLatest(): String = if (::outputText.isInitialized) outputText.text.toString() else latestOutput

    private fun persistSession() {
        try {
            val editor = prefs.edit()
                .putString("audit_history_v1", audit.takeLast(300).joinToString("\n"))
                .putString("last_task_prompt_v1", lastTaskPrompt)
                .putString("last_task_status_v1", lastTaskStatus)
                .putString("last_output_v1", outputTextOrLatest())
                .putString("last_memory_json_v1", buildTaskMemoryJson().toString())
            editor.apply()
        } catch (_: Exception) { /* Keep UI responsive; next explicit export may still work. */ }
    }

    private fun restoreSession() {
        lastTaskPrompt = prefs.getString("last_task_prompt_v1", "").orEmpty()
        lastTaskStatus = prefs.getString("last_task_status_v1", "لم يبدأ التنفيذ").orEmpty()
        latestOutput = prefs.getString("last_output_v1", "").orEmpty()
        val savedZipPath = prefs.getString("latest_zip_path_v1", "").orEmpty()
        if (savedZipPath.isNotBlank()) {
            val savedZip = java.io.File(savedZipPath)
            if (savedZip.exists() && savedZip.length() > 0L) latestZip = savedZip
        }
        if (lastTaskPrompt.isNotBlank()) promptInput.setText(lastTaskPrompt)
        if (latestOutput.isNotBlank()) outputText.setText(latestOutput)
        val savedAudit = prefs.getString("audit_history_v1", "").orEmpty()
        if (savedAudit.isNotBlank()) audit.addAll(savedAudit.split("\n").takeLast(300))
        val memory = prefs.getString("last_memory_json_v1", null)
        if (memory != null) {
            try {
                val saved = JSONObject(memory)
                lastReviewScore = saved.takeIf { !it.isNull("review_score_out_of_100") }?.optInt("review_score_out_of_100")
                val savedStates = saved.optJSONObject("model_states")
                if (savedStates != null) models.forEach { model ->
                    if (savedStates.has(model.name)) modelStates[model.id] = savedStates.optString(model.name)
                }
                val savedProposals = saved.optJSONArray("proposals")
                if (savedProposals != null) for (i in 0 until savedProposals.length()) {
                    val item = savedProposals.optJSONObject(i) ?: continue
                    proposalArchive.add(item.optString("model") to item.optString("content_excerpt"))
                }
                val savedReviews = saved.optJSONArray("reviews")
                if (savedReviews != null) for (i in 0 until savedReviews.length()) {
                    val item = savedReviews.optJSONObject(i) ?: continue
                    reviewArchive.add(item.optString("model") to item.optString("review_excerpt"))
                }
            } catch (_: Exception) { lastReviewScore = null }
        }
        refreshAudit()
    }

    private fun copyText(label: String, value: String) {
        if (value.isBlank()) { toast("لا يوجد محتوى لنسخه: $label"); return }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
        toast("تم نسخ $label.")
    }

    private fun setBusy(busy: Boolean, message: String = "") {
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (message.isNotBlank()) setStatus(message)
    }

    private fun safeError(e: Throwable): String =
        (e.message ?: e.javaClass.simpleName).replace(Regex("(?i)(api[_ -]?key|authorization)\\s*[:=]\\s*\\S+"), "\$1=[redacted]").take(220)

    private fun modelName(id: String) = models.firstOrNull { it.id == id }?.name ?: id.take(8)

    private fun saveModels() {
        val array = JSONArray()
        models.forEach { m ->
            array.put(JSONObject().put("id", m.id).put("name", m.name).put("provider", m.provider)
                .put("endpoint", m.endpoint).put("modelId", m.modelId).put("encryptedKey", m.encryptedKey)
                .put("role", m.role))
        }
        prefs.edit().putString("models", array.toString()).apply()
    }

    private fun loadModels() {
        var registryReadFailed = false
        try {
            val array = JSONArray(prefs.getString("models", "[]"))
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                models.add(AiModel(o.getString("id"), o.getString("name"), o.getString("provider"),
                    o.getString("endpoint"), o.getString("modelId"), o.optString("encryptedKey"),
                    o.optString("role", "Coder")))
            }
        } catch (_: Exception) {
            // Do not silently replace a registry that may contain encrypted API keys.
            registryReadFailed = true
        }
        // Seed defaults only on a genuinely empty, readable registry. Never replace existing encrypted keys.
        if (models.isEmpty() && !registryReadFailed) {
            val defaults = listOf(
                AiModel("default_groq_120b", "Groq Llama 3.3", "Groq", "https://api.groq.com/openai/v1/chat/completions", "openai/gpt-oss-120b", "", "Planner/Leader"),
                AiModel("default_groq_20b", "Groq Agent 2", "Groq", "https://api.groq.com/openai/v1/chat/completions", "openai/gpt-oss-20b", "", "Reviewer"),
                AiModel("default_openrouter_free", "OpenRouter Coder", "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "openrouter/free", "", "Coder"),
                AiModel("default_poolside", "Poolside Laguna", "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "poolside/laguna-s-2.1:free", "", "Coder"),
                AiModel("default_nvidia", "NVIDIA Nemotron", "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "nvidia/nemotron-3-ultra-550b-a55b:free", "", "Reviewer"),
                AiModel("default_gemini", "Gemini 3.8 Flash", "Gemini", "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent", "gemini-3.8-flash", "", "Prompt Engineer")
            )
            models.addAll(defaults)
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
            android.util.Base64.encodeToString(iv + encrypted, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            toast("تعذر تشفير المفتاح: ${safeError(e)}")
            ""
        }
    }

    private fun decryptKey(value: String): String {
        if (value.isBlank()) return ""
        return try {
            val all = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
            val iv = all.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(all.copyOfRange(12, all.size)), StandardCharsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val alias = "BasselAI.ApiKey.AES"
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    data class AiModel(
        val id: String,
        val name: String,
        val provider: String,
        val endpoint: String,
        val modelId: String,
        val encryptedKey: String,
        val role: String
    )

    companion object { private const val REQUEST_EXPORT = 7204 }
}