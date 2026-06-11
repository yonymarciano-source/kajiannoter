package com.kajian.note.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.kajian.note.db.NoteRepository
import com.kajian.note.model.Folder
import com.kajian.note.model.Note
import com.kajian.note.utils.UserManager
import kotlinx.coroutines.launch

class RecordViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = NoteRepository(app)
    private val gson = Gson()

    // ── Notes & Folders ───────────────────────────────────────────────────────
    val allNotes: LiveData<List<Note>> = repo.all
    val allFolders: LiveData<List<Folder>> = repo.allFolders

    // ── Transcript state ──────────────────────────────────────────────────────
    private val _transcript = MutableLiveData<String>("")
    val transcript: LiveData<String> = _transcript

    // ── Recording state ───────────────────────────────────────────────────────
    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> = _isRecording

    // ── Language ──────────────────────────────────────────────────────────────
    private val _language = MutableLiveData("id-ID")
    val language: LiveData<String> = _language

    // ── Save result & error (for RecordFragment observers) ────────────────────
    val saveResult = MutableLiveData<Long>(0L)
    val error = MutableLiveData<String>("")

    // ── Premium tier ──────────────────────────────────────────────────────────
    private val _tier = MutableLiveData(UserManager.Tier.FREE)
    val tier: LiveData<UserManager.Tier> = _tier

    init {
        viewModelScope.launch {
            _tier.value = UserManager.getTier()
        }
    }

    // ── Public methods ────────────────────────────────────────────────────────

    fun refreshTier() = viewModelScope.launch {
        _tier.value = UserManager.getTier(forceRefresh = true)
    }

    fun setRecording(recording: Boolean) { _isRecording.value = recording }

    fun setLanguage(lang: String) { _language.value = lang }

    fun updateText(text: String) { _transcript.value = text }

    fun appendTranscript(text: String) {
        _transcript.value = (_transcript.value ?: "") + " " + text
    }

    fun clearTranscript() { _transcript.value = "" }

    fun setTranscript(text: String) { _transcript.value = text }

    // ── Save note (v3.x style — called from RecordFragment) ───────────────────
    fun saveNote(
        title: String,
        entries: List<Any>,
        plainText: String,
        language: String,
        speakerNames: Map<Any, Any>,
        durationMs: Long,
        audioPath: String
    ) = viewModelScope.launch {
        val note = Note(
            title           = title,
            plainText       = plainText,
            detectedLanguage = language,
            durationMs      = durationMs,
            audioPath       = audioPath,
            speakerNamesJson = gson.toJson(speakerNames),
            transcriptJson  = gson.toJson(entries)
        )
        val id = repo.insert(note)
        saveResult.postValue(id)
    }

    // ── Save note (v4.0 style — direct Note object) ───────────────────────────
    fun saveNote(note: Note) = viewModelScope.launch {
        val id = repo.insert(note)
        saveResult.postValue(id)
    }

    fun updateNote(note: Note) = viewModelScope.launch { repo.update(note) }
    fun deleteNote(note: Note) = viewModelScope.launch { repo.delete(note) }

    fun searchNotes(q: String) = repo.search(q)
    fun getNotesByFolder(folderId: Long) = repo.getByFolder(folderId)

    fun createFolder(folder: Folder) = viewModelScope.launch { repo.insertFolder(folder) }
    fun updateFolder(folder: Folder) = viewModelScope.launch { repo.updateFolder(folder) }
    fun deleteFolder(folder: Folder) = viewModelScope.launch { repo.deleteFolder(folder) }

    suspend fun canAddNote(): Boolean {
        val count = repo.countAll()
        return UserManager.canAddNote(count)
    }
}
