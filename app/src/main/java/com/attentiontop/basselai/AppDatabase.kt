
package com.attentiontop.basselai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Project(
    val id: Long,
    val request: String,
    val status: String,
    val plan: String?,
    val decision: String?,
    val createdAt: Long,
    val updatedAt: Long
)

class AppDatabase(context: Context) : SQLiteOpenHelper(
    context,
    "bassel_ai.db",
    null,
    1
) {

    companion object {
        const val ANALYSIS = "ANALYSIS"
        const val WAITING_FOR_APPROVAL = "WAITING_FOR_APPROVAL"
        const val APPROVED = "APPROVED"
        const val REJECTED = "REJECTED"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                request TEXT NOT NULL,
                status TEXT NOT NULL,
                plan TEXT,
                decision TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX idx_projects_status ON projects(status)"
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // لا نحذف بيانات المستخدم عند الترقية.
        // تُضاف تغييرات المخطط مستقبلًا بترقيات صريحة.
    }

    fun createProject(request: String): Long {
        require(request.isNotBlank()) {
            "يجب إدخال طلب المشروع."
        }

        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("request", request.trim())
            put("status", ANALYSIS)
            put("created_at", now)
            put("updated_at", now)
        }

        return writableDatabase.insertOrThrow(
            "projects",
            null,
            values
        )
    }

    /**
     * يحفظ الخطة وينقل المشروع إلى انتظار الموافقة
     * ضمن معاملة SQLite واحدة.
     */
    fun savePlanAndWaitApproval(
        projectId: Long,
        plan: String
    ) {
        require(plan.isNotBlank()) {
            "خطة التحليل فارغة."
        }

        val db = writableDatabase
        db.beginTransaction()

        try {
            val values = ContentValues().apply {
                put("plan", plan)
                put("status", WAITING_FOR_APPROVAL)
                put("updated_at", System.currentTimeMillis())
            }

            val changed = db.update(
                "projects",
                values,
                "id = ? AND status = ?",
                arrayOf(projectId.toString(), ANALYSIS)
            )

            check(changed == 1) {
                "تعذر حفظ الخطة: المشروع غير موجود أو حالته تغيّرت."
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * لا يسمح بتسجيل القرار إلا للمشروع الذي ينتظر الموافقة.
     */
    fun recordDecision(
        projectId: Long,
        approved: Boolean
    ) {
        val decision = if (approved) "APPROVED" else "REJECTED"
        val now = System.currentTimeMillis()
        val db = writableDatabase

        val values = ContentValues().apply {
            put("decision", decision)
            put("status", decision)
            put("updated_at", now)
        }

        val changed = db.update(
            "projects",
            values,
            "id = ? AND status = ?",
            arrayOf(
                projectId.toString(),
                WAITING_FOR_APPROVAL
            )
        )

        check(changed == 1) {
            "لم يُسجل القرار: المشروع لا ينتظر الموافقة."
        }
    }

    fun getProject(projectId: Long): Project? {
        readableDatabase.query(
            "projects",
            null,
            "id = ?",
            arrayOf(projectId.toString()),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null

            return Project(
                id = cursor.getLong(
                    cursor.getColumnIndexOrThrow("id")
                ),
                request = cursor.getString(
                    cursor.getColumnIndexOrThrow("request")
                ),
                status = cursor.getString(
                    cursor.getColumnIndexOrThrow("status")
                ),
                plan = cursor.getString(
                    cursor.getColumnIndexOrThrow("plan")
                ),
                decision = cursor.getString(
                    cursor.getColumnIndexOrThrow("decision")
                ),
                createdAt = cursor.getLong(
                    cursor.getColumnIndexOrThrow("created_at")
                ),
                updatedAt = cursor.getLong(
                    cursor.getColumnIndexOrThrow("updated_at")
                )
            )
        }
    }

    fun getLatestProject(): Project? {
        readableDatabase.query(
            "projects",
            null,
            null,
            null,
            null,
            null,
            "id DESC",
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null

            return Project(
                id = cursor.getLong(
                    cursor.getColumnIndexOrThrow("id")
                ),
                request = cursor.getString(
                    cursor.getColumnIndexOrThrow("request")
                ),
                status = cursor.getString(
                    cursor.getColumnIndexOrThrow("status")
                ),
                plan = cursor.getString(
                    cursor.getColumnIndexOrThrow("plan")
                ),
                decision = cursor.getString(
                    cursor.getColumnIndexOrThrow("decision")
                ),
                createdAt = cursor.getLong(
                    cursor.getColumnIndexOrThrow("created_at")
                ),
                updatedAt = cursor.getLong(
                    cursor.getColumnIndexOrThrow("updated_at")
                )
            )
        }
    }
}
