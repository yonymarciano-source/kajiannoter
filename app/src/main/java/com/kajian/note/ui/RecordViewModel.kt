package com.kajian.note.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.kajian.note.db.NoteRepository
import com.kajian.note.model.Folder
import com.kajian.note.model.Note
import com.kajian.note.utils.UserManager
import kotlinx.coroutines.launch

class RecordViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = NoteRepository(app)

    val allNotes: LiveData<List<Note>> = repo.all
    val allFolders: LiveData<List<Folder>> = repo.allFolders

    // Current transcript being built
    private val _transcript = MutableLiveData<String>("")
    val transcript: LiveData<String> = _transcript

    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> = _isRecording

    // Premium tier state
    private val _tier = MutableLiveData(UserManager.Tier.FREE)
    val tier: LiveData<UserManager.Tier> = _tier

    init {
        // Load tier on init
        viewModelScope.launch {
            _tier.value = UserManager.getTier()
        }
    }

    fun refreshTier() {
        viewModelScope.launch {
            _tier.value = UserManager.getTier(forceRefresh = true)
        }
    }

    fun setRecording(recording: Boolean) { _isRecording.value = recording }
    fun appendTranscript(text: String) {
        _transcript.value = (_transcript.value ?: "") + " " + text
    }
    fun clearTranscript() { _transcript.value = "" }
    fun setTranscript(text: String) { _transcript.value = text }

    fun searchNotes(q: String) = repo.search(q)
    fun getNotesByFolder(folderId: Long) = repo.getByFolder(folderId)

    fun saveNote(note: Note) = viewModelScope.launch { repo.insert(note) }
    fun updateNote(note: Note) = viewModelScope.launch { repo.update(note) }
    fun deleteNote(note: Note) = viewModelScope.launch { repo.delete(note) }

    fun createFolder(folder: Folder) = viewModelScope.launch { repo.insertFolder(folder) }
    fun updateFolder(folder: Folder) = viewModelScope.launch { repo.updateFolder(folder) }
    fun deleteFolder(folder: Folder) = viewModelScope.launch { repo.deleteFolder(folder) }

    /**
     * Cek apakah user bisa tambah catatan baru.
     * Return true = boleh, false = perlu upgrade.
     */
    suspend fun canAddNote(): Boolean {
        val count = repo.countAll()
        return UserManager.canAddNote(count)
    }
}

    // ── v3.x compatibility helpers ─────────────────────────────────────────────
    private val _language = MutableLiveData("id-ID")
    val language: LiveData<String> = _language
    fun setLanguage(lang: String) { _language.value = lang }
    fun updateText(text: String) { _transcript.value = text }

    fun saveNote(
        title: String,
        entries: List<Any>,
        plainText: String,
        language: String,
        speakerNames: Map<Any, Any>,
        durationMs: Long,
        audioPath: String
    ) = viewModelScope.launch {
        val note = com.kajian.note.model.Note(
            title = title,
            plainText = plainText,
            detectedLanguage = language,
            durationMs = durationMs,
            audioPath = audioPath,
            speakerNamesJson = com.google.gson.Gson().toJson(speakerNames),
            transcriptJson = com.google.gson.Gson().toJson(entries)
        )
        repo.insert(note)
    }

    val saveResult = MutableLiveData<Long>(0L)
    val error = MutableLiveData<String>("")
