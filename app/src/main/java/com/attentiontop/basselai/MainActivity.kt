package com.attentiontop.basselai

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
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

    private var currentProjectId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDatabase(this)
        buildInterface()
        restoreLatestProject()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.rgb(247, 248, 250))
        }

        val scroll = ScrollView(this)
        scroll.addView(root)

        root.addView(
            label("Bassel AI", 28f, true).apply {
                gravity = Gravity.CENTER
            },
            params()
        )

        root.addView(
            label(
                "تحليل الطلبات وإعداد الخطط بمساعدة الذكاء الاصطناعي",
                16f
            ).apply {
                gravity = Gravity.CENTER
            },
            params()
        )

        root.addView(label("محرك الذكاء الاصطناعي", 18f, true), params())

        engineSpinner = Spinner(this)
        engineSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Gemini", "Groq")
        )
        root.addView(engineSpinner, params())

        root.addView(label("اكتب طلبك", 18f, true), params())

        requestInput = EditText(this).apply {
            hint = "اكتب الطلب الذي تريد تحليله"
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.RIGHT
            textSize = 16f
        }
        root.addView(requestInput, params())

        sendButton = Button(this).apply {
            text = "تحليل الطلب"
            isAllCaps = false
            setOnClickListener {
                hideKeyboard()
                submitRequest()
            }
        }
        root.addView(sendButton, params())

        root.addView(label("حالة المشروع", 18f, true), params())

        statusText = label("جاهز", 16f)
        root.addView(statusText, params())

        root.addView(label("الخطة والتحليل", 18f, true), params())

        resultText = label("ستظهر نتيجة التحليل هنا.", 16f)
        resultText.setPadding(12, 12, 12, 12)
        resultText.setBackgroundColor(Color.WHITE)
        root.addView(resultText, params())

        approveButton = Button(this).apply {
            text = "الموافقة على الخطة"
            isAllCaps = false
            setOnClickListener {
                recordDecision(true)
            }
        }
        root.addView(approveButton, params())

        rejectButton = Button(this).apply {
            text = "رفض الخطة"
            isAllCaps = false
            setOnClickListener {
                recordDecision(false)
            }
        }
        root.addView(rejectButton, params())

        updateDecisionButtons(null)
        setContentView(scroll)
    }

    private fun label(
        value: String,
        size: Float,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        gravity = Gravity.RIGHT
        setTextColor(Color.rgb(35, 50, 75))
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, 8, 0, 8)
    }

    private fun params() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        setMargins(0, 4, 0, 8)
    }

    private fun hideKeyboard() {
        val manager = getSystemService(
            Context.INPUT_METHOD_SERVICE
        ) as InputMethodManager

        manager.hideSoftInputFromWindow(requestInput.windowToken, 0)
        requestInput.clearFocus()
    }

    private fun submitRequest() {
        val request = requestInput.text.toString().trim()

        if (request.isBlank()) {
            statusText.text = "اكتب طلبًا أولاً."
            return
        }

        val engine = engineSpinner.selectedItem.toString()

        val apiKey = when (engine) {
            "Gemini" -> BuildConfig.GEMINI_API_KEY
            "Groq" -> BuildConfig.GROQ_API_KEY
            else -> ""
        }

        if (apiKey.isBlank()) {
            statusText.text = "مفتاح $engine غير موجود في نسخة التطبيق."
            return
        }

        sendButton.isEnabled = false
        updateDecisionButtons(null)
        statusText.text = "جارٍ تحليل الطلب باستخدام $engine..."
        resultText.text = ""

        lifecycleScope.launch {
            try {
                val projectId = withContext(Dispatchers.IO) {
                    db.createProject(request)
                }

                currentProjectId = projectId

                val plan = withContext(Dispatchers.IO) {
                    when (engine) {
                        "Gemini" -> requestGemini(request, apiKey)
                        "Groq" -> requestGroq(request, apiKey)
                        else -> error("محرك غير معروف.")
                    }
                }

                withContext(Dispatchers.IO) {
                    db.savePlanAndWaitApproval(projectId, plan)
                }

                resultText.text = plan
                statusText.text = "اكتمل التحليل. الخطة تنتظر موافقتك."
                updateDecisionButtons("WAITING_FOR_APPROVAL")

            } catch (e: Exception) {
                statusText.text =
                    "فشل التحليل: ${e.message ?: "خطأ غير معروف"}"
            } finally {
                sendButton.isEnabled = true
            }
        }
    }

    private fun requestGemini(
        request: String,
        apiKey: String
    ): String {
        val systemPrompt = """
            أنت مساعد Bassel AI.
            حلل طلب المستخدم باللغة العربية.
            قدم خطة واضحة ومرقمة وخطوات عملية.
            لا تدّع تنفيذ إجراءات خارجية.
            الخطة مقترح يحتاج إلى موافقة المستخدم.
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
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put("text", request)
                            )
                        )
                    }
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
                                حلل الطلب باللغة العربية.
                                قدم خطة واضحة ومرقمة وخطوات عملية.
                                لا تدّع تنفيذ إجراءات خارجية.
                                الخطة مقترح يحتاج إلى موافقة المستخدم.
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
                            ?: responseText.take(400)
                    }"
                )
            }

            return JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun recordDecision(approved: Boolean) {
        if (currentProjectId <= 0L) {
            statusText.text = "لا يوجد مشروع لاتخاذ قرار بشأنه."
            return
        }

        approveButton.isEnabled = false
        rejectButton.isEnabled = false

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.recordDecision(currentProjectId, approved)
                }

                statusText.text = if (approved) {
                    "تم تسجيل الموافقة."
                } else {
                    "تم تسجيل الرفض."
                }

                updateDecisionButtons(
                    if (approved) "APPROVED" else "REJECTED"
                )
            } catch (e: Exception) {
                statusText.text =
                    "تعذر تسجيل القرار: ${e.message}"

                updateDecisionButtons("WAITING_FOR_APPROVAL")
            }
        }
    }

    private fun restoreLatestProject() {
        lifecycleScope.launch {
            try {
                val project = withContext(Dispatchers.IO) {
                    db.getLatestProject()
                } ?: return@launch

                currentProjectId = project.id
                requestInput.setText(project.request)
                resultText.text = project.plan ?: "لا توجد خطة محفوظة."

                statusText.text = when (project.status) {
                    "ANALYSIS" ->
                        "الطلب محفوظ ولم يكتمل تحليله."

                    "WAITING_FOR_APPROVAL" ->
                        "الخطة تنتظر موافقتك."

                    "APPROVED" ->
                        "تمت الموافقة على الخطة."

                    "REJECTED" ->
                        "تم رفض الخطة."

                    else ->
                        "حالة المشروع: ${project.status}"
                }

                updateDecisionButtons(project.status)
            } catch (e: Exception) {
                statusText.text =
                    "تعذر استرجاع المشروع: ${e.message}"
            }
        }
    }

    private fun updateDecisionButtons(status: String?) {
        val waiting = status == "WAITING_FOR_APPROVAL"
        approveButton.isEnabled = waiting
        rejectButton.isEnabled = waiting
    }

    override fun onDestroy() {
        if (::db.isInitialized) {
            db.close()
        }
        super.onDestroy()
    }
}
