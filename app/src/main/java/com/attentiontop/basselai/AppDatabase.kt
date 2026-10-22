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

class AppDatabase(context: Context) :
SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

companion object {
    private const val DATABASE_NAME = "bassel_ai.db"
    private const val DATABASE_VERSION = 1

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
            plan TEXT,
            status TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
        """.trimIndent()
    )
}

override fun onUpgrade(
    db: SQLiteDatabase,
    oldVersion: Int,
    newVersion: Int
) {
    // لا نحذف بيانات المستخدم عند تحديث قاعدة البيانات.
}

fun createProject(request: String): Long {
    require(request.isNotBlank()) {
        "الطلب لا يمكن أن يكون فارغًا."
    }

    val values = ContentValues().apply {
        put("request", request)
        put("plan", null as String?)
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
    val values = ContentValues().apply {
        put("plan", plan)
        put("status", WAITING_FOR_APPROVAL)
        put("updated_at", "datetime('now')")
    }

    db.beginTransaction()
    try {
        val updateValues = ContentValues().apply {
            put("plan", plan)
            put("status", WAITING_FOR_APPROVAL)
        }

        val changed = db.update(
            "projects",
            updateValues,
            "id = ? AND status = ?",
            arrayOf(projectId.toString(), ANALYSIS)
        )

        check(changed == 1) {
            "لم يُعثر على المشروع أو أن حالته لم تعد ANALYSIS."
        }

        db.execSQL(
            "UPDATE projects SET updated_at = CURRENT_TIMESTAMP WHERE id = ?",
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

    val values = ContentValues().apply {
        put("status", newStatus)
    }

    db.beginTransaction()
    try {
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
            "لا يمكن تسجيل القرار: المشروع غير موجود أو لا ينتظر الموافقة."
        }

        db.execSQL(
            "UPDATE projects SET updated_at = CURRENT_TIMESTAMP WHERE id = ?",
            arrayOf(projectId)
        )

        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
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
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            request = cursor.getString(
                cursor.getColumnIndexOrThrow("request")
            ),
            plan = if (
                cursor.isNull(cursor.getColumnIndexOrThrow("plan"))
            ) null else cursor.getString(
                cursor.getColumnIndexOrThrow("plan")
            ),
            status = cursor.getString(
                cursor.getColumnIndexOrThrow("status")
            )
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
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            request = cursor.getString(
                cursor.getColumnIndexOrThrow("request")
            ),
            plan = if (
                cursor.isNull(cursor.getColumnIndexOrThrow("plan"))
            ) null else cursor.getString(
                cursor.getColumnIndexOrThrow("plan")
            ),
            status = cursor.getString(
                cursor.getColumnIndexOrThrow("status")
            )
        )
    }
}

}
