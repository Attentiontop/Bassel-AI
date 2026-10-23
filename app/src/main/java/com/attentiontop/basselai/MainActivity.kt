package com.attentiontop.basselai

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
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

        // تهيئة قاعدة البيانات المحلية
        try {
            db = AppDatabase.getDatabase(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // بناء واجهة مستخدم احترافية ومنظمة داخل التطبيق برمجياً
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // عنوان التطبيق
        val titleText = TextView(this).apply {
            text = "Bassel AI Manager"
            textSize = 22f
            setTextColor(Color.parseColor("#333333"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }
        rootLayout.addView(titleText)

        // حقل إدخال مفتاح الـ API
        val apiKeyInput = EditText(this).apply {
            hint = "أدخل مفتاح الـ API هنا"
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.WHITE)
            textSize = 14f
        }
        rootLayout.addView(apiKeyInput)

        // مسافة فاصلة
        val spacer1 = TextView(this).apply { height = 20 }
        rootLayout.addView(spacer1)

        // حقل إدخال الرسالة أو الـ Prompt
        val promptInput = EditText(this).apply {
            hint = "اكتب رسالتك للذكاء الاصطناعي..."
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.WHITE)
            textSize = 14f
        }
        rootLayout.addView(promptInput)

        // مسافة فاصلة
        val spacer2 = TextView(this).apply { height = 30 }
        rootLayout.addView(spacer2)

        // زر إرسال الطلب لـ Google Gemini
        val geminiButton = Button(this).apply {
            text = "إرسال إلى Google Gemini"
            setBackgroundColor(Color.parseColor("#4285F4"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val apiKey = apiKeyInput.text.toString().trim()
                val prompt = promptInput.text.toString().trim()
                sendAiRequest("Google Gemini", apiKey, "gemini-1.5-flash", prompt)
            }
        }
        rootLayout.addView(geminiButton)

        // مسافة فاصلة صغيرة
        rootLayout.addView(TextView(this).apply { height = 15 })

        // زر إرسال الطلب لـ Groq
        val groqButton = Button(this).apply {
            text = "إرسال إلى Groq"
            setBackgroundColor(Color.parseColor("#FF6F00"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val apiKey = apiKeyInput.text.toString().trim()
                val prompt = promptInput.text.toString().trim()
                sendAiRequest("Groq", apiKey, "llama3-70b-8192", prompt)
            }
        }
        rootLayout.addView(groqButton)

        // مسافة فاصلة صغيرة
        rootLayout.addView(TextView(this).apply { height = 15 })

        // زر إرسال الطلب لـ OpenRouter
        val openRouterButton = Button(this).apply {
            text = "إرسال إلى OpenRouter"
            setBackgroundColor(Color.parseColor("#673AB7"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val apiKey = apiKeyInput.text.toString().trim()
                val prompt = promptInput.text.toString().trim()
                sendAiRequest("OpenRouter", apiKey, "meta-llama/llama-3-8b-instruct:free", prompt)
            }
        }
        rootLayout.addView(openRouterButton)

        setContentView(rootLayout)
    }

    // دالة الاتصال الموحدة لدعم المزودين الثلاثة وقاعدة البيانات
    private fun sendAiRequest(provider: String, apiKey: String, modelId: String, prompt: String) {
        if (apiKey.isEmpty() || prompt.isEmpty()) {
            Toast.makeText(this, "يرجى إدخال مفتاح الـ API والرسالة أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        // حفظ المفتاح محلياً في قاعدة البيانات Room
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.modelDao().insertOrUpdateModel(ModelEntity(provider, apiKey, modelId))
            } catch (e: Exception) {
                e.printStackTrace()
            }

            var url = ""
            val jsonBody = JSONObject()
            val mediaType = "application/json; charset=utf-8".toMediaType()

            when (provider) {
                "Google Gemini" -> {
                    url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
                    val partObj = JSONObject().put("text", prompt)
                    val partsArray = JSONArray().put(partObj)
                    val contentObj = JSONObject().put("parts", partsArray)
                    val contentsArray = JSONArray().put(contentObj)
                    jsonBody.put("contents", contentsArray)
                }
                "OpenRouter" -> {
                    url = "https://openrouter.ai/api/v1/chat/completions"
                    jsonBody.put("model", modelId)
                    val messageObj = JSONObject().put("role", "user").put("content", prompt)
                    val messagesArray = JSONArray().put(messageObj)
                    jsonBody.put("messages", messagesArray)
                }
                "Groq" -> {
                    url = "https://api.groq.com/openai/v1/chat/completions"
                    jsonBody.put("model", modelId)
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
                        Toast.makeText(this@MainActivity, "تمت الإرسال والاستجابة بنجاح!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "خطأ في الاتصال: ${response.code}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "خطأ شبكة: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
