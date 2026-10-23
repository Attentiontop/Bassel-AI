package com.attentiontop.basselai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "bassel_ai.db"
        private const val DATABASE_VERSION = 2

        const val TABLE_PROJECTS = "projects"
        const val COLUMN_ID = "id"
        const val COLUMN_REQUEST = "request"
        const val COLUMN_PLAN = "plan"
        const val COLUMN_STATUS = "status"
        const val COLUMN_CREATED_AT = "created_at"
        const val COLUMN_UPDATED_AT = "updated_at"

        const val TABLE_ATTEMPTS = "attempts"
        const val ATTEMPT_ID = "id"
        const val ATTEMPT_PROJECT_ID = "project_id"
        const val ATTEMPT_PROVIDER = "provider"
        const val ATTEMPT_ROLE = "role"
        const val ATTEMPT_OUTPUT = "output"
        const val ATTEMPT_ERROR = "error"
        const val ATTEMPT_DURATION_MS = "duration_ms"
        const val ATTEMPT_CREATED_AT = "created_at"

        const val STATUS_ANALYSIS = "ANALYSIS"
        const val STATUS_WAITING_FOR_APPROVAL = "WAITING_FOR_APPROVAL"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_COMPLETED = "COMPLETED"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createProjectsTable = ("CREATE TABLE $TABLE_PROJECTS ("
                + "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "$COLUMN_REQUEST TEXT NOT NULL, "
                + "$COLUMN_PLAN TEXT, "
                + "$COLUMN_STATUS TEXT NOT NULL, "
                + "$COLUMN_CREATED_AT TEXT NOT NULL, "
                + "$COLUMN_UPDATED_AT TEXT NOT NULL)")
        db.execSQL(createProjectsTable)

        val createAttemptsTable = ("CREATE TABLE $TABLE_ATTEMPTS ("
                + "$ATTEMPT_ID INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "$ATTEMPT_PROJECT_ID INTEGER NOT NULL, "
                + "$ATTEMPT_PROVIDER TEXT, "
                + "$ATTEMPT_ROLE TEXT, "
                + "$ATTEMPT_OUTPUT TEXT, "
                + "$ATTEMPT_ERROR TEXT, "
                + "$ATTEMPT_DURATION_MS INTEGER, "
                + "$ATTEMPT_CREATED_AT TEXT NOT NULL, "
                + "FOREIGN KEY($ATTEMPT_PROJECT_ID) REFERENCES $TABLE_PROJECTS($COLUMN_ID) ON DELETE CASCADE)")
        db.execSQL(createAttemptsTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_ATTEMPTS (" +
                    "$ATTEMPT_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$ATTEMPT_PROJECT_ID INTEGER NOT NULL, " +
                    "$ATTEMPT_PROVIDER TEXT, " +
                    "$ATTEMPT_ROLE TEXT, " +
                    "$ATTEMPT_OUTPUT TEXT, " +
                    "$ATTEMPT_ERROR TEXT, " +
                    "$ATTEMPT_DURATION_MS INTEGER, " +
                    "$ATTEMPT_CREATED_AT TEXT NOT NULL)")
        }
    }

    private fun getCurrentTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }

    fun createProject(request: String): Long {
        val db = this.writableDatabase
        val currentTime = getCurrentTimestamp()
        val values = ContentValues().apply {
            put(COLUMN_REQUEST, request)
            put(COLUMN_PLAN, "")
            put(COLUMN_STATUS, STATUS_ANALYSIS)
            put(COLUMN_CREATED_AT, currentTime)
            put(COLUMN_UPDATED_AT, currentTime)
        }
        return db.insert(TABLE_PROJECTS, null, values)
    }

    fun savePlanAndWaitApproval(projectId: Long, plan: String): Boolean {
        val db = this.writableDatabase
        val currentTime = getCurrentTimestamp()
        val values = ContentValues().apply {
            put(COLUMN_PLAN, plan)
            put(COLUMN_STATUS, STATUS_WAITING_FOR_APPROVAL)
            put(COLUMN_UPDATED_AT, currentTime)
        }
        val rowsAffected = db.update(TABLE_PROJECTS, values, "$COLUMN_ID = ?", arrayOf(projectId.toString()))
        return rowsAffected > 0
    }

    fun logAttempt(projectId: Long, provider: String, role: String, output: String, error: String, durationMs: Long) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(ATTEMPT_PROJECT_ID, projectId)
            put(ATTEMPT_PROVIDER, provider)
            put(ATTEMPT_ROLE, role)
            put(ATTEMPT_OUTPUT, output)
            put(ATTEMPT_ERROR, error)
            put(ATTEMPT_DURATION_MS, durationMs)
            put(ATTEMPT_CREATED_AT, getCurrentTimestamp())
        }
        db.insert(TABLE_ATTEMPTS, null, values)
    }

    fun updateProjectStatus(projectId: Long, status: String): Boolean {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_STATUS, status)
            put(COLUMN_UPDATED_AT, getCurrentTimestamp())
        }
        return db.update(TABLE_PROJECTS, values, "$COLUMN_ID = ?", arrayOf(projectId.toString())) > 0
    }

    fun getLatestProject(): ProjectModel? {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_PROJECTS ORDER BY $COLUMN_ID DESC LIMIT 1", null)
        var project: ProjectModel? = null
        if (cursor.moveToFirst()) {
            project = ProjectModel(
                id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                request = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_REQUEST)),
                plan = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PLAN)) ?: "",
                status = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_STATUS))
            )
        }
        cursor.close()
        return project
    }
}

data class ProjectModel(
    val id: Long,
    val request: String,
    val plan: String,
    val status: String
)
