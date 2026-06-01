package com.kajian.note.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.kajian.note.db.NoteRepository
import com.kajian.note.model.Note
import com.kajian.note.model.TranscriptEntry
import kotlinx.coroutines.launch

class RecordViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = NoteRepository(app)
    val allNotes: LiveData<List<Note>> = repo.all
    fun searchNotes(q: String) = repo.search(q)

    // ── Recording state ───────────────────────────────────────────────────
    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> = _isRecording

    private val _language = MutableLiveData("auto")
    val language: LiveData<String> = _language

    private val _fullText = MutableLiveData("")
    val fullText: LiveData<String> = _fullText

    private val _partial = MutableLiveData("")
    val partial: LiveData<String> = _partial

    private val _rms = MutableLiveData(0f)
    val rms: LiveData<Float> = _rms

    private val _currentSpeaker = MutableLiveData("Speaker A")
    val currentSpeaker: LiveData<String> = _currentSpeaker

    private val _speakerChangePrompt = MutableLiveData<Int?>(null)
    val speakerChangePrompt: LiveData<Int?> = _speakerChangePrompt

    private val _saveResult = MutableLiveData<Long>(-1L)
    val saveResult: LiveData<Long> = _saveResult

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    var recordingStartMs = 0L

    // ── Setters ────────────────────────────────────────────────────────────

    fun setRecording(v: Boolean) {
        _isRecording.value = v
        if (v) recordingStartMs = System.currentTimeMillis()
    }

    fun setLanguage(lang: String) { _language.value = lang }
    fun updateText(t: String) { _fullText.value = t }
    fun updatePartial(t: String) { _partial.value = t }
    fun updateRms(r: Float) { _rms.value = r }
    fun updateSpeaker(name: String) { _currentSpeaker.value = name }
    fun promptSpeakerChange(newIdx: Int) { _speakerChangePrompt.value = newIdx }
    fun dismissSpeakerPrompt() { _speakerChangePrompt.value = null }

    // ── Save ───────────────────────────────────────────────────────────────

    fun saveNote(
        title: String,
        entries: List<TranscriptEntry>,
        plainText: String,
        language: String,
        speakerNames: Map<Int, String>,
        durationMs: Long,
        audioPath: String = ""
    ) {
        if (plainText.isBlank() && entries.isEmpty()) {
            _error.value = "Nothing to save — transcript is empty"
            return
        }
        viewModelScope.launch {
            try {
                val gson = Gson()
                val speakerCount = (entries.mapNotNull {
                    if (it.type == TranscriptEntry.TYPE_SPEECH) it.speakerIndex else null
                }.toSet().size).coerceAtLeast(1)

                val note = Note(
                    title = title.ifBlank { autoTitle(plainText) },
                    transcriptJson = gson.toJson(entries),
                    plainText = plainText.trim(),
                    detectedLanguage = language,
                    speakerNamesJson = gson.toJson(speakerNames),
                    durationMs = durationMs,
                    wordCount = plainText.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.size,
                    speakerCount = speakerCount,
                    audioPath = audioPath
                )
                val id = repo.insert(note)
                _saveResult.postValue(id)
            } catch (e: Exception) {
                _error.postValue("Save failed: ${e.message}")
            }
        }
    }

    fun updateNote(note: Note) = viewModelScope.launch { repo.update(note) }
    fun deleteNote(note: Note) = viewModelScope.launch { repo.delete(note) }

    private fun autoTitle(text: String): String {
        val words = text.trim().split("\\s+".toRegex())
        return words.take(7).joinToString(" ").let {
            if (words.size > 7) "$it…" else it
        }
    }
}
