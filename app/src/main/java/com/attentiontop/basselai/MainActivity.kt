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
    private lateinit var resultTextView: TextView
    private lateinit var statusTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // تهيئة قاعدة البيانات المحلية
        try {
            db = AppDatabase.getDatabase(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // تخطيط الشاشة الرئيسي (خلفية داكنة متوافقة مع تصميم التطبيق الأصلي)
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121824"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        // 1. عنوان مجلس الإدارة العلوي
        val titleText = TextView(this).apply {
            text = "مجلس الإدارة والوكلاء الذكي - Bassel AI"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 20)
        }
        contentLayout.addView(titleText)

        // أزرار الشريط العلوي (الأرشيف، السجل، المفاتيح)
        val topBarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        
        val btnArchive = createStyledButton("📁 الأرشيف", Color.parseColor("#1F293D")) {
            Toast.makeText(this@MainActivity, "الأرشيف قيد التطوير", Toast.LENGTH_SHORT).show()
        }
        val btnLog = createStyledButton("📊 السجل", Color.parseColor("#1F293D")) {
            Toast.makeText(this@MainActivity, "السجل قيد التطوير", Toast.LENGTH_SHORT).show()
        }
        val btnKeys = createStyledButton("⚙️ المفاتيح", Color.parseColor("#1F293D")) {
            Toast.makeText(this@MainActivity, "إدارة المفاتيح", Toast.LENGTH_SHORT).show()
        }

        topBarLayout.addView(btnKeys)
        topBarLayout.addView(btnLog)
        topBarLayout.addView(btnArchive)
        contentLayout.addView(topBarLayout)

        // 2. حالة المحرك والحالة العامة
        statusTextView = TextView(this).apply {
            text = "الحالة: جاهز للعمل (عبر Groq / Gemini)"
            textSize = 13f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 20, 0, 10)
        }
        contentLayout.addView(statusTextView)

        // حقل إدخال الطلب (Prompt)
        val promptInput = EditText(this).apply {
            hint = "اكتب طلبك أو مشروعك الهندسي هنا..."
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setTextColor(Color.WHITE)
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#1E293B"))
            textSize = 14f
        }
        contentLayout.addView(promptInput)

        contentLayout.addView(TextView(this).apply { height = 15 })

        // 3. زر التخطيط الهندسي (PLANNER)
        val btnPlanner = Button(this).apply {
            text = "🚀 بدء التخطيط الهندسي (PLANNER)"
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val prompt = promptInput.text.toString().trim()
                if (prompt.isNotEmpty()) {
                    sendAiRequest("Google Gemini", "AIzaSy...", "gemini-1.5-flash", prompt) // استخدم مفتاحك أو اجعله يقرأ من الحقل
                } else {
                    Toast.makeText(this@MainActivity, "يرجى كتابة الطلب أولاً", Toast.LENGTH_SHORT).show()
                }
            }
        }
        contentLayout.addView(btnPlanner)

        // 4. أزراير الرفض والاعتماد
        val decisionLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 15, 0, 15)
        }
        val btnReject = Button(this).apply {
            text = "❌ رفض"
            setBackgroundColor(Color.parseColor("#DC2626"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                statusTextView.text = "الحالة: تم رفض الخطة."
            }
        }
        val btnAccept = Button(this).apply {
            text = "✅ اعتماد وتنفيذ الكود"
            setBackgroundColor(Color.parseColor("#16A34A"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                statusTextView.text = "الحالة: جاري التنفيذ..."
            }
        }
        decisionLayout.addView(btnReject)
        decisionLayout.addView(btnAccept)
        contentLayout.addView(decisionLayout)

        // 5. منطقة عرض النتائج والخطط
        resultTextView = TextView(this).apply {
            text = "--- مرحباً بك في Bassel AI ---\nجاهز لتلقي مهامك البرمجية والهندسية."
            textSize = 13f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        contentLayout.addView(resultTextView)

        scrollView.addView(contentLayout)
        mainLayout.addView(scrollView)

        // 6. الشريط السفلي (نسخ، مشاركة، مشروع جديد)
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(10, 15, 10, 15)
            gravity = Gravity.CENTER
        }

        val btnNewProject = createBottomButton("➕ مشروع جديد") {
            promptInput.setText("")
            resultTextView.text = "تم البدء بمشروع جديد."
        }
        val btnShare = createBottomButton("📤 مشاركة") {
            Toast.makeText(this, "مشاركة المشروع", Toast.LENGTH_SHORT).show()
        }
        val btnCopy = createBottomButton("📋 نسخ") {
            Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show()
        }

        bottomBar.addView(btnCopy)
        bottomBar.addView(btnShare)
        bottomBar.addView(btnNewProject)
        mainLayout.addView(bottomBar)

        setContentView(mainLayout)
    }

    private fun createStyledButton(text: String, bgColor: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setBackgroundColor(bgColor)
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(15, 10, 15, 10)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(5, 0, 5, 0)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createBottomButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setBackgroundColor(Color.parseColor("#3B82F6"))
            setTextColor(Color.WHITE)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(5, 0, 5, 0)
            }
            setOnClickListener { onClick() }
        }
    }

    // دالة الاتصال المحدثة مع تصحيح مسار Gemini (v1beta) لمنع خطأ 404
    private fun sendAiRequest(provider: String, apiKey: String, modelId: String, prompt: String) {
        statusTextView.text = "الحالة: جاري الإرسال عبر Gemini (v1beta)..."

        CoroutineScope(Dispatchers.IO).launch {
            var url = ""
            val jsonBody = JSONObject()
            val mediaType = "application/json; charset=utf-8".toMediaType()

            // مسار v1beta المصحح لضمان عدم حدوث خطأ 404 نهائياً
            url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
            val partObj = JSONObject().put("text", prompt)
            val partsArray = JSONArray().put(partObj)
            val contentObj = JSONObject().put("parts", partsArray)
            val contentsArray = JSONArray().put(contentObj)
            jsonBody.put("contents", contentsArray)

            val body = jsonBody.toString().toRequestBody(mediaType)
            val request = Request.Builder().url(url).post(body)
                .addHeader("Content-Type", "application/json")
                .build()

            try {
                val response = client.newCall(request).execute()
                val responseData = response.body?.string()

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && responseData != null) {
                        statusTextView.text = "الحالة: الخطة جاهزة (عبر Gemini)"
                        resultTextView.text = responseData
                    } else {
                        statusTextView.text = "الحالة: فشل في التنفيذ (خطأ ${response.code})"
                        resultTextView.text = "تعذر إتمام العملية.\nالسبب: $responseData"
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    statusTextView.text = "الحالة: خطأ في الاتصال بالشبكة"
                    resultTextView.text = "خطأ: ${e.localizedMessage}"
                }
            }
        }
    }
}
