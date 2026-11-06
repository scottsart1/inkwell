package com.inkwell.notes.core.storage.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A person with the number of recordings they are labelled in. */
data class PersonWithCount(
    @Embedded val person: PersonEntity,
    val recordingCount: Int,
)

@Dao
interface SpeakerDao {
    // ---- people

    @Query(
        "SELECT p.*, (SELECT COUNT(DISTINCT rs.recordingId) FROM recording_speakers rs WHERE rs.personId = p.id) AS recordingCount " +
            "FROM people p ORDER BY p.isMe DESC, p.name COLLATE NOCASE",
    )
    fun observePeople(): Flow<List<PersonWithCount>>

    @Query("SELECT * FROM people ORDER BY isMe DESC, name COLLATE NOCASE")
    suspend fun people(): List<PersonEntity>

    @Query("SELECT * FROM people WHERE id = :id")
    suspend fun person(id: String): PersonEntity?

    @Query("SELECT * FROM people WHERE name = :name COLLATE NOCASE ORDER BY createdAt, id LIMIT 1")
    suspend fun personNamed(name: String): PersonEntity?

    @Upsert
    suspend fun upsertPerson(p: PersonEntity)

    @Query("DELETE FROM people WHERE id = :id")
    suspend fun deletePerson(id: String)

    @Query("UPDATE people SET isMe = 0 WHERE isMe = 1 AND id != :keepId")
    suspend fun clearMeExcept(keepId: String)

    @Query("UPDATE people SET voiceSamples = 0")
    suspend fun clearAllVoiceSamples()

    // ---- speaker engine additions

    /** Recordings [personId] is labelled in. */
    @Query("SELECT COUNT(DISTINCT recordingId) FROM recording_speakers WHERE personId = :personId")
    suspend fun recordingCountOf(personId: String): Int

    @Query("UPDATE people SET voiceSamples = :count, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setVoiceSamples(id: String, count: Int, updatedAt: Long)

    /** Every recording id (to find voice data left behind by deleted recordings). */
    @Query("SELECT id FROM recordings")
    suspend fun allRecordingIds(): List<String>

    // ---- recording speakers

    @Query("SELECT * FROM recording_speakers WHERE recordingId = :recordingId ORDER BY label")
    fun observeSpeakers(recordingId: String): Flow<List<RecordingSpeakerEntity>>

    @Query("SELECT * FROM recording_speakers WHERE recordingId = :recordingId ORDER BY label")
    suspend fun speakers(recordingId: String): List<RecordingSpeakerEntity>

    @Query("SELECT * FROM recording_speakers WHERE personId = :personId")
    suspend fun speakersOf(personId: String): List<RecordingSpeakerEntity>

    @Upsert
    suspend fun upsertSpeakers(rows: List<RecordingSpeakerEntity>)

    @Query("DELETE FROM recording_speakers WHERE recordingId = :recordingId")
    suspend fun deleteSpeakers(recordingId: String)

    @Query("DELETE FROM recording_speakers WHERE recordingId = :recordingId AND label = :label")
    suspend fun deleteSpeaker(recordingId: String, label: String)

    // ---- turns

    @Query("SELECT * FROM speaker_turns WHERE recordingId = :recordingId ORDER BY startMs")
    fun observeTurns(recordingId: String): Flow<List<SpeakerTurnEntity>>

    @Query("SELECT * FROM speaker_turns WHERE recordingId = :recordingId ORDER BY startMs")
    suspend fun turns(recordingId: String): List<SpeakerTurnEntity>

    @Insert
    suspend fun insertTurns(rows: List<SpeakerTurnEntity>)

    @Query("DELETE FROM speaker_turns WHERE recordingId = :recordingId")
    suspend fun deleteTurns(recordingId: String)

    @Transaction
    suspend fun replaceTurns(recordingId: String, rows: List<SpeakerTurnEntity>) {
        deleteTurns(recordingId)
        insertTurns(rows)
    }

    // ---- transcript labels

    /** Writes [name] onto every transcript block of one voice. */
    @Query("UPDATE transcript_segments SET speaker = :name WHERE recordingId = :recordingId AND speakerLabel = :label")
    suspend fun setSegmentNames(recordingId: String, label: String, name: String?)

    /** Moves one transcript block (identified by its start on the timeline) to another voice. */
    @Query("UPDATE transcript_segments SET speakerLabel = :label, speaker = :name WHERE recordingId = :recordingId AND startMs = :startMs")
    suspend fun setSegmentSpeaker(recordingId: String, startMs: Long, label: String?, name: String?)

    @Query("UPDATE transcript_segments SET speakerLabel = :intoLabel WHERE recordingId = :recordingId AND speakerLabel = :fromLabel")
    suspend fun relabelSegments(recordingId: String, fromLabel: String, intoLabel: String)

    @Query("UPDATE speaker_turns SET label = :intoLabel WHERE recordingId = :recordingId AND label = :fromLabel")
    suspend fun relabelTurns(recordingId: String, fromLabel: String, intoLabel: String)
}
