package com.example.data.local.model

import com.example.data.local.entity.PatientDocumentEntity
import com.example.data.local.entity.PatientEntity
import com.example.data.local.entity.PatientImageEntity
import com.example.data.local.entity.SurgeryEntity
import com.example.data.local.entity.TimelineEventEntity
import java.util.Calendar

/** Lightweight row for the patient list/search screens. */
data class PatientSummary(
    val id: String,
    val patientCode: String,
    val fullName: String,
    val age: Int?,
    val gender: String,
    val diagnosis: String,
    val bodyRegion: String,
    val side: String,
    val isArchived: Boolean,
    val updatedAt: Long
)

/** Full patient profile screen: demographics + medical/orthopedic data + everything attached. */
data class PatientDetail(
    val patient: PatientEntity,
    val age: Int?,
    val timeline: PatientTimeline,
    val surgeries: List<SurgeryEntity>,
    val images: List<PatientImageEntity>,
    val documents: List<PatientDocumentEntity>
)

enum class TimelineBucket { PAST, CURRENT, FUTURE }

/**
 * Timeline events split into previous/current/future buckets, derived from
 * [TimelineEventEntity.eventDate] relative to now — never a stored, staleness-prone flag.
 * An event counts as CURRENT if it falls on today's calendar date.
 */
data class PatientTimeline(
    val past: List<TimelineEventEntity>,
    val current: List<TimelineEventEntity>,
    val future: List<TimelineEventEntity>
) {
    val all: List<TimelineEventEntity> get() = past + current + future

    companion object {
        fun bucket(events: List<TimelineEventEntity>, now: Long = System.currentTimeMillis()): PatientTimeline {
            val todayStart = startOfDay(now)
            val todayEnd = todayStart + DAY_MILLIS
            val past = mutableListOf<TimelineEventEntity>()
            val current = mutableListOf<TimelineEventEntity>()
            val future = mutableListOf<TimelineEventEntity>()
            events.forEach { event ->
                when {
                    event.eventDate < todayStart -> past.add(event)
                    event.eventDate < todayEnd -> current.add(event)
                    else -> future.add(event)
                }
            }
            return PatientTimeline(
                past = past.sortedByDescending { it.eventDate },
                current = current.sortedByDescending { it.eventDate },
                future = future.sortedBy { it.eventDate }
            )
        }

        private const val DAY_MILLIS = 24L * 60 * 60 * 1000

        private fun startOfDay(timeMillis: Long): Long {
            val cal = Calendar.getInstance()
            cal.timeInMillis = timeMillis
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}

/** A search hit across patients, surgeries, or OCR'd media — for the unified search screen. */
data class PatientSearchResult(
    val patientId: String,
    val patientName: String,
    val patientCode: String,
    val matchType: PatientSearchMatchType,
    val snippet: String
)

enum class PatientSearchMatchType { NAME, PATIENT_ID, DIAGNOSIS, FRACTURE_TYPE, SURGERY, OCR_TEXT }

/**
 * Age in whole years as of today, or null if [dateOfBirth] is unset. Kept as a plain
 * function (not stored) so it's always accurate without a background recompute job.
 */
fun calculateAge(dateOfBirth: Long?, now: Long = System.currentTimeMillis()): Int? {
    if (dateOfBirth == null) return null
    val birth = Calendar.getInstance().apply { timeInMillis = dateOfBirth }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    var age = today.get(Calendar.YEAR) - birth.get(Calendar.YEAR)
    if (today.get(Calendar.DAY_OF_YEAR) < birth.get(Calendar.DAY_OF_YEAR)) {
        age -= 1
    }
    return age.coerceAtLeast(0)
}

fun PatientEntity.toSummary(): PatientSummary = PatientSummary(
    id = id,
    patientCode = patientCode,
    fullName = fullName,
    age = calculateAge(dateOfBirth),
    gender = gender,
    diagnosis = diagnosis,
    bodyRegion = bodyRegion,
    side = side,
    isArchived = isArchived,
    updatedAt = updatedAt
)

/** A timeline event plus who it belongs to, for the widget and daily overviews. */
data class TimelineEventWithPatient(
    val id: String,
    val eventDate: Long,
    val title: String,
    val description: String,
    val attendance: String,
    val remark: String,
    val patientName: String,
    val patientId: String
)
