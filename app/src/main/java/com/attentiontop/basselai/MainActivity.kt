package com.attentiontop.basselai

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.EditText
import android.graphics.Typeface
import android.graphics.Color
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.res.ColorStateList
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

private lateinit var db: AppDatabase
private lateinit var requestInput: EditText
private lateinit var engineSpinner: Spinner
private lateinit var statusText: TextView
private lateinit var resultText: TextView
private lateinit var sendButton: Button
private lateinit var approveButton: Button
private lateinit var rejectButton: Button

private var currentProjectId: Long = -1L

private val engines = arrayOf("Gemini", "Groq")

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

    val title = TextView(this).apply {
        text = "Bassel AI"
        textSize = 28f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.rgb(25, 45, 80))
        gravity = Gravity.CENTER
    }
    root.addView(title, matchWidth())

    val subtitle = TextView(this).apply {
        text = "مساعد ذكي لتحليل الطلبات وإعداد الخطط"
        textSize = 16f
        gravity = Gravity.CENTER
        setPadding(0, 8, 0, 20)
    }
    root.addView(subtitle, matchWidth())

    addLabel(root, "اختر محرك الذكاء الاصطناعي")

    engineSpinner = Spinner(this)
    engineSpinner.adapter = ArrayAdapter(
        this,
        android.R.layout.simple_spinner_dropdown_item,
        engines
    )
    root.addView(engineSpinner, matchWidth())

    addLabel(root, "اكتب طلبك")

    requestInput = EditText(this).apply {
        hint = "اشرح ما تريد من Bassel AI"
        minLines = 4
        maxLines = 8
        gravity = Gravity.TOP or Gravity.RIGHT
        textSize = 16f
        setPadding(12, 12, 12, 12)
        setBackgroundColor(Color.WHITE)
    }
    root.addView(
        requestInput,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, 16)
        }
    )

    sendButton = makeButton("تحليل الطلب")
    root.addView(sendButton, matchWidth())

    addLabel(root, "حالة المشروع")

    statusText = TextView(this).apply {
        text = "لا يوجد طلب بعد"
        textSize = 16f
        setPadding(0, 8, 0, 12)
    }
    root.addView(statusText, matchWidth())

    addLabel(root, "الخطة والتحليل")

    resultText = TextView(this).apply {
        text = "ستظهر الخطة هنا بعد التحليل."
        textSize = 16f
        setTextColor(Color.DKGRAY)
        setPadding(12, 12, 12, 12)
        setBackgroundColor(Color.WHITE)
        gravity = Gravity.RIGHT
    }
    root.addView(
        resultText,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, 16)
        }
    )

    approveButton = makeButton("موافقة على الخطة")
    root.addView(approveButton, matchWidth())

    rejectButton = makeButton("رفض الخطة")
    root.addView(rejectButton, matchWidth())

    sendButton.setOnClickListener {
        hideKeyboard()
        submitRequest()
    }

    approveButton.setOnClickListener {
        recordDecision(true)
    }

    rejectButton.setOnClickListener {
        recordDecision(false)
    }

    updateDecisionButtons(null)

    setContentView(scroll)
}

private fun matchWidth(): LinearLayout.LayoutParams {
    return LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        setMargins(0, 0, 0, 12)
    }
}

private fun addLabel(parent: LinearLayout, value: String) {
    val label = TextView(this).apply {
        text = value
        textSize = 17f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.rgb(35, 50, 75))
        setPadding(0, 12, 0, 8)
    }
    parent.addView(label, matchWidth())
}

private fun makeButton(value: String): Button {
    return Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 16f
    }
}

private fun hideKeyboard() {
    val manager = getSystemService(
        Context.INPUT_METHOD_SERVICE
    ) as InputMethodManager

    manager.hideSoftInputFromWindow(
        requestInput.windowToken,
        0
    )
    requestInput.clearFocus()
}

private fun submitRequest() {
    val request = requestInput.text.toString().trim()

    if (request.isEmpty()) {
        statusText.text = "اكتب الطلب أولاً."
        return
    }

    val engine = engineSpinner.selectedItem.toString()

    val apiKey = when (engine) {
        "Gemini" -> BuildConfig.GEMINI_API_KEY
        "Groq" -> BuildConfig.GROQ_API_KEY
        else -> ""
    }

    if (apiKey.isBlank()) {
        statusText.text =
            "مفتاح $engine غير موجود داخل التطبيق. تحقق من إعدادات البناء."
        return
    }

    sendButton.isEnabled = false
    updateDecisionButtons(null)
    statusText.text = "جارٍ إرسال الطلب إلى $engine..."
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
                    else -> error("محرك غير معروف")
                }
            }

            withContext(Dispatchers.IO) {
                db.savePlanAndWaitApproval(projectId, plan)
            }

            resultText.text = plan
            statusText.text =
                "اكتمل التحليل عبر $engine. الخطة تنتظر موافقتك."
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
        قدم خطة واضحة ومرقمة، وحدد الخطوات والنتيجة المتوقعة.
        لا تدّع تنفيذ أي إجراء خارجي.
        الخطة مقترح يحتاج إلى موافقة المستخدم قبل اعتماده.
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
        url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
        apiKey = apiKey,
        headerName = "x-goog-api-key",
        body = body,
        bearer = false
    )

    val candidates = response.optJSONArray("candidates")
        ?: error("لم يُرجع Gemini أي نتائج.")

    val parts = candidates.optJSONObject(0)
        ?.optJSONObject("content")
        ?.optJSONArray("parts")
        ?: error("استجابة Gemini لا تحتوي على نص.")

    val output = StringBuilder()

    for (i in 0 until parts.length()) {
        val part = parts.optJSONObject(i)
        val text = part?.optString("text").orEmpty()
        if (text.isNotBlank()) {
            if (output.isNotEmpty()) output.append("\n")
            output.append(text)
        }
    }

    return output.toString().trim().ifEmpty {
        error("أعاد Gemini نتيجة فارغة.")
    }
}

private fun requestGroq(
    request: String,
    apiKey: String
): String {
    val body = JSONObject().apply {
        put("model", "llama-3.3-70b-versatile")
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
                            قدم خطة واضحة ومرقمة، وحدد الخطوات والنتيجة المتوقعة.
                            لا تدّع تنفيذ أي إجراء خارجي.
                            الخطة مقترح يحتاج إلى موافقة المستخدم قبل اعتماده.
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
        url = "https://api.groq.com/openai/v1/chat/completions",
        apiKey = apiKey,
        headerName = "Authorization",
        body = body,
        bearer = true
    )

    val choices = response.optJSONArray("choices")
        ?: error("لم يُرجع Groq أي نتائج.")

    return choices.optJSONObject(0)
        ?.optJSONObject("message")
        ?.optString("content")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: error("أعاد Groq نتيجة فارغة.")
}

private fun postJson(
    url: String,
    apiKey: String,
    headerName: String,
    body: JSONObject,
    bearer: Boolean
): JSONObject {
    val connection = (URL(url).openConnection()
        as HttpURLConnection)

    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        connection.doOutput = true
        connection.setRequestProperty(
            "Content-Type",
            "application/json; charset=UTF-8"
        )

        val headerValue = if (bearer) {
            "Bearer $apiKey"
        } else {
            apiKey
        }

        connection.setRequestProperty(
            headerName,
            headerValue
        )

        connection.outputStream.use { stream ->
            stream.write(body.toString().toByteArray(Charsets.UTF_8))
        }

        val statusCode = connection.responseCode

        val responseStream = if (statusCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        val responseText = responseStream?.bufferedReader(
            Charsets.UTF_8
        )?.use { it.readText() }.orEmpty()

        if (statusCode !in 200..299) {
            val message = try {
                JSONObject(responseText)
                    .optJSONObject("error")
                    ?.optString("message")
            } catch (_: Exception) {
                null
            }

            error(
                "HTTP $statusCode: " +
                    (message?.takeIf { it.isNotBlank() }
                        ?: responseText.take(500))
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
                "تعذر تسجيل القرار: ${e.message ?: "خطأ غير معروف"}"
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
            resultText.text = project.plan
                ?: "لا توجد خطة محفوظة لهذا الطلب."

            statusText.text = when (project.status) {
                "ANALYSIS" -> "الطلب محفوظ، لكن التحليل لم يكتمل."
                "WAITING_FOR_APPROVAL" ->
                    "الخطة تنتظر موافقتك."
                "APPROVED" -> "تمت الموافقة على الخطة."
                "REJECTED" -> "تم رفض الخطة."
                else -> "حالة المشروع: ${project.status}"
            }

            updateDecisionButtons(project.status)

        } catch (e: Exception) {
            statusText.text =
                "تعذر استرجاع المشروع: ${e.message ?: "خطأ غير معروف"}"
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
