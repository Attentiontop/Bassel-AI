
package com.attentiontop.basselai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    private lateinit var db: AppDatabase
    private lateinit var requestInput: EditText
    private lateinit var engineSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var sendButton: Button
    private lateinit var approveButton: Button
    private lateinit var rejectButton: Button
    private lateinit var copyButton: Button

    private var currentProjectId = -1L
    private var busy = false
    private var currentStatus: String? = null

    private val background = Color.rgb(17, 24, 39)
    private val surface = Color.rgb(31, 41, 55)
    private val foreground = Color.rgb(243, 244, 246)
    private val muted = Color.rgb(156, 163, 175)
    private val accent = Color.rgb(96, 165, 250)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        db = AppDatabase(this)
        buildInterface()
        restoreLatestProject()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 24)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(background)
        }

        val scroll = ScrollView(this)
        scroll.addView(root)

        root.addView(
            label("Bassel AI", 30f, true).apply {
                gravity = Gravity.CENTER
                setTextColor(accent)
            },
            params()
        )

        root.addView(
            label("مساعدك لتحليل الطلبات وإعداد الخطط", 15f)
                .apply {
                    gravity = Gravity.CENTER
                    setTextColor(muted)
                },
            params()
        )

        root.addView(label("محرك الذكاء الاصطناعي", 17f, true), params())

        engineSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Gemini", "Groq")
            )
        }
        root.addView(engineSpinner, params())

        root.addView(label("اكتب طلبك", 17f, true), params())

        requestInput = EditText(this).apply {
            hint = "اكتب طلبك هنا..."
            setHintTextColor(muted)
            setTextColor(foreground)
            textSize = 16f
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.RIGHT
            setPadding(14, 14, 14, 14)
            setBackgroundColor(surface)
        }
        root.addView(requestInput, params())

        sendButton = makeButton("تحليل الطلب") {
            hideKeyboard()
            submitRequest()
        }
        root.addView(sendButton, params())

        root.addView(label("الحالة", 17f, true), params())

        statusText = label("جاهز", 15f)
        statusText.setTextColor(accent)
        root.addView(statusText, params())

        root.addView(label("النتيجة", 17f, true), params())

        resultText = label("ستظهر الإجابة هنا.", 16f).apply {
            setPadding(14, 14, 14, 14)
            setBackgroundColor(surface)
            setTextIsSelectable(true)
            gravity = Gravity.TOP or Gravity.RIGHT
        }
        root.addView(resultText, params())

        copyButton = makeButton("نسخ الإجابة") {
            copyAnswer()
        }
        root.addView(copyButton, params())

        root.addView(label("قرارك بشأن الخطة", 17f, true), params())

        approveButton = makeButton("الموافقة على الخطة") {
            recordDecision(true)
        }
        root.addView(approveButton, params())

        rejectButton = makeButton("رفض الخطة") {
            recordDecision(false)
        }
        root.addView(rejectButton, params())

        setContentView(scroll)
        updateControls()
    }

    private fun label(
        value: String,
        size: Float,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        gravity = Gravity.RIGHT
        setTextColor(foreground)
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, 8, 0, 8)
    }

    private fun params() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        setMargins(0, 4, 0, 10)
    }

    private fun makeButton(
        title: String,
        action: () -> Unit
    ) = Button(this).apply {
        text = title
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun hideKeyboard() {
        val manager = getSystemService(
            Context.INPUT_METHOD_SERVICE
        ) as InputMethodManager

        manager.hideSoftInputFromWindow(requestInput.windowToken, 0)
        requestInput.clearFocus()
    }

    private fun updateControls() {
        sendButton.isEnabled = !busy

        val waiting =
            !busy &&
            currentStatus == AppDatabase.WAITING_FOR_APPROVAL

        approveButton.isEnabled = waiting
        rejectButton.isEnabled = waiting

        copyButton.isEnabled = resultText.text
            .toString()
            .isNotBlank()
    }

    private fun submitRequest() {
        if (busy) return

        val request = requestInput.text.toString().trim()

        if (request.isBlank()) {
            statusText.text = "اكتب طلبًا أولاً."
            return
        }

        val directAnswer = simpleReply(request)

        if (directAnswer != null) {
            busy = true
            currentStatus = null
            updateControls()
            statusText.text = "جارٍ حفظ الإجابة..."

            lifecycleScope.launch {
                try {
                    val id = withContext(Dispatchers.IO) {
                        db.createProject(request).also {
                            db.saveDirectAnswer(it, directAnswer)
                        }
                    }

                    currentProjectId = id
                    currentStatus = AppDatabase.COMPLETED
                    resultText.text = directAnswer
                    statusText.text = "إجابة مباشرة"
                } catch (e: Exception) {
                    statusText.text =
                        "تعذر حفظ الإجابة: ${e.message}"
                    resultText.text = directAnswer
                } finally {
                    busy = false
                    updateControls()
                }
            }
            return
        }

        val engine = engineSpinner.selectedItem.toString()
        val apiKey = when (engine) {
            "Gemini" -> BuildConfig.GEMINI_API_KEY
            "Groq" -> BuildConfig.GROQ_API_KEY
            else -> ""
        }

        if (apiKey.isBlank()) {
            statusText.text = "مفتاح $engine غير موجود."
            return
        }

        busy = true
        currentStatus = AppDatabase.ANALYSIS
        resultText.text = ""
        statusText.text = "جارٍ تحليل الطلب باستخدام $engine..."
        updateControls()

        lifecycleScope.launch {
            var projectId = -1L

            try {
                projectId = withContext(Dispatchers.IO) {
                    db.createProject(request)
                }

                currentProjectId = projectId

                val started = System.currentTimeMillis()

                val plan = withContext(Dispatchers.IO) {
                    when (engine) {
                        "Gemini" -> requestGemini(request, apiKey)
                        "Groq" -> requestGroq(request, apiKey)
                        else -> error("محرك غير معروف.")
                    }
                }

                val duration = System.currentTimeMillis() - started

                withContext(Dispatchers.IO) {
                    db.recordAttempt(
                        projectId = projectId,
                        provider = engine,
                        role = "planning",
                        output = plan,
                        durationMs = duration
                    )
                    db.savePlanAndWaitApproval(projectId, plan)
                }

                currentStatus = AppDatabase.WAITING_FOR_APPROVAL
                resultText.text = plan
                statusText.text = "اكتمل التحليل. الخطة تنتظر موافقتك."

            } catch (e: Exception) {
                val message = e.message ?: "خطأ غير معروف"

                if (projectId > 0L) {
                    withContext(Dispatchers.IO) {
                        try {
                            db.recordAttempt(
                                projectId = projectId,
                                provider = engine,
                                role = "planning",
                                error = message
                            )
                            db.recordFailure(projectId, message)
                        } catch (_: Exception) {
                        }
                    }
                }

                currentStatus = if (projectId > 0L) {
                    AppDatabase.FAILED
                } else {
                    null
                }

                statusText.text = "فشل التحليل: $message"
                resultText.text = "لم تكتمل العملية. لم تُحفظ خطة ناجحة."
            } finally {
                busy = false
                updateControls()
            }
        }
    }

    private fun simpleReply(request: String): String? {
        val normalized = request
            .trim()
            .lowercase()
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("آ", "ا")
            .replace("ة", "ه")
            .replace("ـ", "")
            .trim(' ', '.', '!', '؟', '?', '،')

        return when (normalized) {
            "مرحبا", "مرحبا بك", "السلام عليكم",
            "السلام عليكم ورحمة الله وبركاته" ->
                "وعليكم السلام ورحمة الله وبركاته!"

            "اهلا", "اهلا بك", "اهلين", "هلا" ->
                "أهلًا بك! كيف يمكنني مساعدتك؟"

            "صباح الخير" ->
                "صباح النور!"

            "مساء الخير" ->
                "مساء النور!"

            "شكرا", "شكرا لك", "بارك الله فيك" ->
                "العفو! أنا هنا لمساعدتك."

            "من انت", "ما اسمك" ->
                "أنا Bassel AI، مساعدك لتنظيم الطلبات وتحليلها."

            else -> null
        }
    }

    private fun requestGemini(
        request: String,
        apiKey: String
    ): String {
        val systemPrompt = """
            أنت مساعد Bassel AI.
            أجب باللغة العربية بوضوح ودقة.
            إذا كان الطلب سؤالًا مباشرًا فأجب مباشرة.
            إذا كان يتطلب خطة، فقدم خطوات عملية مرقمة.
            لا تدّع تنفيذ إجراءات خارجية.
            ميّز بين الحقائق والافتراضات.
        """.trimIndent()

        val body = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put("text", systemPrompt)
                    )
                )
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put("text", request)
                        )
                    )
                )
            )
        }

        val response = postJson(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
            apiKey,
            "x-goog-api-key",
            body,
            false
        )

        val candidates = response.optJSONArray("candidates")
            ?: error("لم يُرجع Gemini نتائج.")

        val parts = candidates.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: error("استجابة Gemini لا تحتوي على نص.")

        val output = (0 until parts.length()).mapNotNull { index ->
            parts.optJSONObject(index)
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
        }.joinToString("\n").trim()

        return output.ifBlank {
            error("أعاد Gemini نتيجة فارغة.")
        }
    }

    private fun requestGroq(
        request: String,
        apiKey: String
    ): String {
        val body = JSONObject().apply {
            put("model", "openai/gpt-oss-120b")
            put("temperature", 0.2)
            put("max_completion_tokens", 2048)
            put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject().apply {
                            put("role", "system")
                            put(
                                "content",
                                """
                                أنت مساعد Bassel AI.
                                أجب باللغة العربية بوضوح ودقة.
                                أجب مباشرة عن الأسئلة البسيطة.
                                استخدم خطة مرقمة عند الحاجة فقط.
                                لا تدّع تنفيذ إجراءات خارجية.
                                """.trimIndent()
                            )
                        }
                    )
                    .put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", request)
                        }
                    )
            )
        }

        val response = postJson(
            "https://api.groq.com/openai/v1/chat/completions",
            apiKey,
            "Authorization",
            body,
            true
        )

        val choices = response.optJSONArray("choices")
            ?: error("لم يُرجع Groq نتائج.")

        return choices.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: error("أعاد Groq نتيجة فارغة.")
    }

    private fun postJson(
        url: String,
        apiKey: String,
        headerName: String,
        body: JSONObject,
        bearer: Boolean
    ): JSONObject {
        val connection =
            URL(url).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.doOutput = true

            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )

            connection.setRequestProperty(
                headerName,
                if (bearer) "Bearer $apiKey" else apiKey
            )

            connection.outputStream.use { stream ->
                stream.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode

            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = stream
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            if (code !in 200..299) {
                val message = try {
                    JSONObject(responseText)
                        .optJSONObject("error")
                        ?.optString("message")
                } catch (_: Exception) {
                    null
                }

                error(
                    "HTTP $code: ${
                        message?.takeIf { it.isNotBlank() }
                            ?: responseText.take(300)
                    }"
                )
            }

            return JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun recordDecision(approved: Boolean) {
        if (busy) return

        if (currentProjectId <= 0L ||
            currentStatus != AppDatabase.WAITING_FOR_APPROVAL
        ) {
            statusText.text = "لا توجد خطة تنتظر القرار."
            return
        }

        busy = true
        updateControls()
        statusText.text = "جارٍ تسجيل القرار..."

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.recordDecision(currentProjectId, approved)
                }

                currentStatus = if (approved) {
                    AppDatabase.APPROVED
                } else {
                    AppDatabase.REJECTED
                }

                statusText.text = if (approved) {
                    "تم تسجيل الموافقة. لم يُنفذ أي إجراء تلقائيًا."
                } else {
                    "تم تسجيل رفض الخطة."
                }
            } catch (e: Exception) {
                statusText.text =
                    "تعذر تسجيل القرار: ${e.message}"

                restoreLatestProject()
            } finally {
                busy = false
                updateControls()
            }
        }
    }

    private fun copyAnswer() {
        val answer = resultText.text.toString().trim()

        if (answer.isBlank() || answer == "ستظهر الإجابة هنا.") {
            statusText.text = "لا توجد إجابة لنسخها."
            return
        }

        try {
            val clipboard = getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

            clipboard.setPrimaryClip(
                ClipData.newPlainText("Bassel AI", answer)
            )

            statusText.text = "تم نسخ الإجابة."
        } catch (e: Exception) {
            statusText.text = "تعذر النسخ: ${e.message}"
        }
    }

    private fun restoreLatestProject() {
        if (busy) return

        lifecycleScope.launch {
            try {
                val project = withContext(Dispatchers.IO) {
                    db.getLatestProject()
                } ?: return@launch

                currentProjectId = project.id
                currentStatus = project.status

                requestInput.setText(project.request)
                resultText.text = project.plan ?: ""

                statusText.text = when (project.status) {
                    AppDatabase.ANALYSIS ->
                        "الطلب محفوظ، ولم يكتمل تحليله."

                    AppDatabase.WAITING_FOR_APPROVAL ->
                        "الخطة تنتظر موافقتك."

                    AppDatabase.APPROVED ->
                        "تمت الموافقة. لم يُنفذ إجراء تلقائيًا."

                    AppDatabase.REJECTED ->
                        "تم رفض الخطة."

                    AppDatabase.FAILED ->
                        "فشل التحليل. يمكنك إرسال الطلب مجددًا."

                    AppDatabase.COMPLETED ->
                        "إجابة مباشرة محفوظة."

                    else -> "حالة المشروع: ${project.status}"
                }

                updateControls()
            } catch (e: Exception) {
                statusText.text =
                    "تعذر استعادة المشروع: ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        if (::db.isInitialized) {
            db.close()
        }
        super.onDestroy()
    }
}
