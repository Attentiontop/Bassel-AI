
package com.attentiontop.basselai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Project(
    val id: Long,
    val request: String,
    val plan: String?,
    val status: String
)

data class Attempt(
    val id: Long,
    val projectId: Long,
    val provider: String,
    val role: String,
    val output: String?,
    val error: String?,
    val durationMs: Long,
    val createdAt: String
)

class AppDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "bassel_ai.db"
        private const val DATABASE_VERSION = 2

        const val ANALYSIS = "ANALYSIS"
        const val WAITING_FOR_APPROVAL = "WAITING_FOR_APPROVAL"
        const val APPROVED = "APPROVED"
        const val REJECTED = "REJECTED"
        const val FAILED = "FAILED"
        const val COMPLETED = "COMPLETED"
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                request TEXT NOT NULL,
                plan TEXT,
                status TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )

        createAttemptsTable(db)
    }

    private fun createAttemptsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS attempts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL,
                provider TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'analysis',
                output TEXT,
                error TEXT,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY(project_id) REFERENCES projects(id)
                    ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_attempts_project
            ON attempts(project_id, id)
            """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < 2) {
            createAttemptsTable(db)
        }
    }

    fun createProject(request: String): Long {
        require(request.isNotBlank()) {
            "الطلب لا يمكن أن يكون فارغًا."
        }

        val values = ContentValues().apply {
            put("request", request)
            putNull("plan")
            put("status", ANALYSIS)
        }

        return writableDatabase.insertOrThrow(
            "projects",
            null,
            values
        )
    }

    fun savePlanAndWaitApproval(
        projectId: Long,
        plan: String
    ) {
        require(plan.isNotBlank()) {
            "الخطة لا يمكن أن تكون فارغة."
        }

        val db = writableDatabase
        db.beginTransaction()

        try {
            val values = ContentValues().apply {
                put("plan", plan)
                put("status", WAITING_FOR_APPROVAL)
                put("updated_at", currentTimestampExpression())
            }

            val changed = db.update(
                "projects",
                values,
                "id = ? AND status = ?",
                arrayOf(projectId.toString(), ANALYSIS)
            )

            check(changed == 1) {
                "لم يُعثر على المشروع أو أن حالته تغيرت."
            }

            db.execSQL(
                """
                UPDATE projects
                SET updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """.trimIndent(),
                arrayOf(projectId)
            )

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /*
     * يسجل إجابة مباشرة دون طلب موافقة.
     */
    fun saveDirectAnswer(
        projectId: Long,
        answer: String
    ) {
        require(answer.isNotBlank()) {
            "الإجابة لا يمكن أن تكون فارغة."
        }

        updateProjectFromAnalysis(
            projectId = projectId,
            newStatus = COMPLETED,
            plan = answer
        )
    }

    fun recordFailure(
        projectId: Long,
        message: String
    ) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("status", FAILED)
        }

        db.beginTransaction()
        try {
            val changed = db.update(
                "projects",
                values,
                "id = ? AND status = ?",
                arrayOf(projectId.toString(), ANALYSIS)
            )

            if (changed == 1) {
                db.execSQL(
                    """
                    UPDATE projects
                    SET updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """.trimIndent(),
                    arrayOf(projectId)
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // الرسالة التفصيلية تحفظ أيضًا في سجل المحاولات
        // عبر recordAttempt() من طبقة الاتصال.
    }

    private fun updateProjectFromAnalysis(
        projectId: Long,
        newStatus: String,
        plan: String
    ) {
        val db = writableDatabase
        db.beginTransaction()

        try {
            val values = ContentValues().apply {
                put("plan", plan)
                put("status", newStatus)
            }

            val changed = db.update(
                "projects",
                values,
                "id = ? AND status = ?",
                arrayOf(projectId.toString(), ANALYSIS)
            )

            check(changed == 1) {
                "تعذر تحديث المشروع: تغيرت حالته أو لم يعد موجودًا."
            }

            db.execSQL(
                """
                UPDATE projects
                SET updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """.trimIndent(),
                arrayOf(projectId)
            )

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun recordDecision(
        projectId: Long,
        approved: Boolean
    ) {
        val newStatus = if (approved) APPROVED else REJECTED
        val db = writableDatabase

        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("status", newStatus)
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
                "تعذر تسجيل القرار: المشروع غير موجود أو لا ينتظر الموافقة."
            }

            db.execSQL(
                """
                UPDATE projects
                SET updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """.trimIndent(),
                arrayOf(projectId)
            )

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun recordAttempt(
        projectId: Long,
        provider: String,
        role: String = "analysis",
        output: String? = null,
        error: String? = null,
        durationMs: Long = 0L
    ): Long {
        require(provider.isNotBlank()) {
            "اسم المزود مطلوب."
        }

        val values = ContentValues().apply {
            put("project_id", projectId)
            put("provider", provider)
            put("role", role)
            if (output == null) putNull("output")
            else put("output", output)

            if (error == null) putNull("error")
            else put("error", error)

            put("duration_ms", durationMs.coerceAtLeast(0L))
        }

        return writableDatabase.insertOrThrow(
            "attempts",
            null,
            values
        )
    }

    fun getAttempts(projectId: Long): List<Attempt> {
        val result = mutableListOf<Attempt>()

        readableDatabase.query(
            "attempts",
            arrayOf(
                "id",
                "project_id",
                "provider",
                "role",
                "output",
                "error",
                "duration_ms",
                "created_at"
            ),
            "project_id = ?",
            arrayOf(projectId.toString()),
            null,
            null,
            "id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    Attempt(
                        id = cursor.getLong(0),
                        projectId = cursor.getLong(1),
                        provider = cursor.getString(2),
                        role = cursor.getString(3),
                        output = if (cursor.isNull(4)) null
                            else cursor.getString(4),
                        error = if (cursor.isNull(5)) null
                            else cursor.getString(5),
                        durationMs = cursor.getLong(6),
                        createdAt = cursor.getString(7)
                    )
                )
            }
        }

        return result
    }

    fun getProject(projectId: Long): Project? {
        readableDatabase.query(
            "projects",
            arrayOf("id", "request", "plan", "status"),
            "id = ?",
            arrayOf(projectId.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null

            return Project(
                id = cursor.getLong(0),
                request = cursor.getString(1),
                plan = if (cursor.isNull(2)) null
                    else cursor.getString(2),
                status = cursor.getString(3)
            )
        }
    }

    fun getLatestProject(): Project? {
        readableDatabase.query(
            "projects",
            arrayOf("id", "request", "plan", "status"),
            null,
            null,
            null,
            null,
            "id DESC",
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null

            return Project(
                id = cursor.getLong(0),
                request = cursor.getString(1),
                plan = if (cursor.isNull(2)) null
                    else cursor.getString(2),
                status = cursor.getString(3)
            )
        }
    }

    private fun currentTimestampExpression(): String {
        // لا تُستخدم هذه القيمة كوقت؛ التحديث الفعلي يتم عبر SQL.
        return ""
    }
}
