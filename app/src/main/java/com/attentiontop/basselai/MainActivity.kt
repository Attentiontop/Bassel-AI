package com.attentiontop.basselai

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // الحل الهندسي الآمن: بناء واجهة النشاط برمجياً بالكامل لتجنب الاعتماد كلياً على ملفات XML مفقودة (R.layout)
        // هذا يمنع خطأ "Unresolved reference 'layout'" نهائياً أثناء مرحلة Compile-time.
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(rootLayout)

        // تهيئة قاعدة البيانات المحلية بأمان
        try {
            db = AppDatabase.getDatabase(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // دالة موحدة لإرسال الطلبات لجميع المزودين (Groq, Gemini, OpenRouter)
    private fun sendAiRequest(provider: String, apiKey: String, modelId: String, prompt: String) {
        CoroutineScope(Dispatchers.IO).launch {
            if (apiKey.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "مفتاح API فارغ", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            var url = ""
            val jsonBody = JSONObject()
            val mediaType = "application/json; charset=utf-8".toMediaType()

            when (provider) {
                "Google Gemini" -> {
                    // مسار v1beta المصحح لمنع خطأ 404 نهائياً
                    url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
                    val partObj = JSONObject().put("text", prompt)
                    val partsArray = JSONArray().put(partObj)
                    val contentObj = JSONObject().put("parts", partsArray)
                    val contentsArray = JSONArray().put(contentObj)
                    jsonBody.put("contents", contentsArray)
                }
                "OpenRouter" -> {
                    url = "https://openrouter.ai/api/v1/chat/completions"
                    jsonBody.put("model", if (modelId.isEmpty()) "meta-llama/llama-3-8b-instruct:free" else modelId)
                    val messageObj = JSONObject().put("role", "user").put("content", prompt)
                    val messagesArray = JSONArray().put(messageObj)
                    jsonBody.put("messages", messagesArray)
                }
                "Groq" -> {
                    url = "https://api.groq.com/openai/v1/chat/completions"
                    jsonBody.put("model", if (modelId.isEmpty()) "llama3-70b-8192" else modelId)
                    val messageObj = JSONObject().put("role", "user").put("content", prompt)
                    val messagesArray = JSONArray().put(messageObj)
                    jsonBody.put("messages", messagesArray)
                }
            }

            val body = jsonBody.toString().toRequestBody(mediaType)
            val requestBuilder = Request.Builder().url(url).post(body)
                .addHeader("Content-Type", "application/json")

            if (provider == "OpenRouter") {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            try {
                val response = client.newCall(requestBuilder.build()).execute()
                val responseData = response.body?.string()

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && responseData != null) {
                        Toast.makeText(this@MainActivity, "تمت الاستجابة بنجاح", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "خطأ في الاتصال: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}
