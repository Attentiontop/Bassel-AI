
package com.attentiontop.basselai

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var database: AppDatabase
    private lateinit var requestInput: EditText
    private lateinit var resultText: TextView
    private lateinit var analyzeButton: Button
    private lateinit var approveButton: Button
    private lateinit var rejectButton: Button

    private var currentProjectId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        database = AppDatabase(this)
        buildInterface()
        restoreLatestProject()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "Bassel AI"
            textSize = 26f
        }

        requestInput = EditText(this).apply {
            hint = "اكتب المهمة التي تريد تحليلها"
            minLines = 3
            gravity = android.view.Gravity.TOP
        }

        analyzeButton = Button(this).apply {
            text = "تحليل الطلب بواسطة Gemini"
            setOnClickListener { analyzeRequest() }
        }

        resultText = TextView(this).apply {
            text = "أدخل طلبًا للبدء."
            textSize = 16f
            setTextIsSelectable(true)
        }

        approveButton = Button(this).apply {
            text = "موافقة"
            isEnabled = false
            setOnClickListener { recordDecision(true) }
        }

        rejectButton = Button(this).apply {
            text = "رفض"
            isEnabled = false
            setOnClickListener { recordDecision(false) }
        }

        root.addView(title)
        root.addView(
            requestInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(analyzeButton)
        root.addView(resultText)
        root.addView(approveButton)
        root.addView(rejectButton)

        val scrollView = ScrollView(this)
        scrollView.addView(root)
        setContentView(scrollView)
    }

    private fun analyzeRequest() {
        val request = requestInput.text.toString().trim()

        if (request.isBlank()) {
            resultText.text = "يرجى كتابة الطلب أولًا."
            return
        }

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) {
            resultText.text =
                "مفتاح Gemini غير مضبوط. أضفه إلى إعدادات البناء."
            return
        }

        analyzeButton.isEnabled = false
        approveButton.isEnabled = false
        rejectButton.isEnabled = false
        resultText.text = "جارٍ حفظ الطلب وإرساله للتحليل..."

        lifecycleScope.launch {
            var projectId: Long? = null

            try {
                projectId = withContext(Dispatchers.IO) {
                    database.createProject(request)
                }
                currentProjectId = projectId

                val plan = withContext(Dispatchers.IO) {
                    requestGemini(request, apiKey)
                }

                withContext(Dispatchers.IO) {
                    database.savePlanAndWaitApproval(
                        projectId,
                        plan
                    )
                }

                resultText.text =
                    "خطة Gemini:\n\n$plan\n\nالحالة: انتظار الموافقة"

                approveButton.isEnabled = true
                rejectButton.isEnabled = true

            } catch (e: Exception) {
                resultText.text =
                    "تعذر إكمال التحليل.\n${e.message ?: "خطأ غير معروف"}\n\n" +
                    "إذا حُفظ الطلب قبل الخطأ، فستبقى حالته محفوظة."
            } finally {
                analyzeButton.isEnabled = true
            }
        }
    }

    private fun requestGemini(
        request: String,
        apiKey: String
    ): String {
        val endpoint =
            "https://generativelanguage.googleapis.com/" +
            "v1beta/models/gemini-3.8-flash:generateContent"

        val body = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            "أنت محرك تحليل في Bassel AI. " +
                            "حلل طلب المستخدم باللغة التي كتب بها. " +
                            "قدّم خطة واضحة ومرقمة، والافتراضات، " +
                            "والمخاطر، والنتيجة المتوقعة. " +
                            "لا تدّع تنفيذ أي إجراء خارجي."
                        )
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

        val connection =
            (URL(endpoint).openConnection() as HttpURLConnection)

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8"
            )
            connection.setRequestProperty(
                "x-goog-api-key",
                apiKey
            )

            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream?.bufferedReader()?.use {
                it.readText()
            }.orEmpty()

            if (status !in 200..299) {
                val detail = try {
                    JSONObject(response)
                        .optJSONObject("error")
                        ?.optString("message")
                } catch (_: Exception) {
                    null
                }

                throw IOException(
                    "Gemini HTTP $status: " +
                    (detail ?: response.take(500))
                )
            }

            val json = JSONObject(response)
            val candidates = json.optJSONArray("candidates")
                ?: throw IOException("لم يُرجع Gemini أي candidates.")

            if (candidates.length() == 0) {
                throw IOException("استجابة Gemini فارغة.")
            }

            val content = candidates
                .getJSONObject(0)
                .optJSONObject("content")
                ?: throw IOException("لا يوجد محتوى في الاستجابة.")

            val parts = content.optJSONArray("parts")
                ?: throw IOException("لا توجد أجزاء نصية في الاستجابة.")

            val text = buildString {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i)
                    val value = part?.optString("text").orEmpty()
                    if (value.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append(value)
                    }
                }
            }.trim()

            if (text.isBlank()) {
                throw IOException("لم يُرجع Gemini نصًا قابلًا للعرض.")
            }

            return text

        } finally {
            connection.disconnect()
        }
    }

    private fun recordDecision(approved: Boolean) {
        val projectId = currentProjectId

        if (projectId == null) {
            resultText.text = "لا يوجد مشروع محدد."
            return
        }

        approveButton.isEnabled = false
        rejectButton.isEnabled = false

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    database.recordDecision(projectId, approved)
                }

                resultText.append(
                    if (approved) {
                        "\n\nتم تسجيل الموافقة."
                    } else {
                        "\n\nتم تسجيل الرفض."
                    }
                )

            } catch (e: Exception) {
                resultText.append(
                    "\n\nتعذر تسجيل القرار: ${e.message}"
                )
                restoreLatestProject()
            }
        }
    }

    private fun restoreLatestProject() {
        lifecycleScope.launch {
            try {
                val project = withContext(Dispatchers.IO) {
                    database.getLatestProject()
                } ?: return@launch

                currentProjectId = project.id
                requestInput.setText(project.request)

                resultText.text = buildString {
                    append("الحالة: ${project.status}\n\n")
                    project.plan?.let {
                        append("الخطة:\n$it\n\n")
                    }
                    project.decision?.let {
                        append("القرار: $it")
                    }
                }

                val waiting =
                    project.status == AppDatabase.WAITING_FOR_APPROVAL

                approveButton.isEnabled = waiting
                rejectButton.isEnabled = waiting

            } catch (e: Exception) {
                resultText.text =
                    "تعذر استعادة المشروع: ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        database.close()
        super.onDestroy()
    }
}
