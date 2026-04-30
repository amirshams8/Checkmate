package com.checkmate.ui.consultation

import android.content.Context
import androidx.lifecycle.ViewModel
import com.checkmate.core.ConsultationProfile
import com.checkmate.core.TimeSlot
import com.checkmate.core.tts.CheckmateTTS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ConsultationViewModel : ViewModel() {

    private val _profile = MutableStateFlow(ConsultationProfile.load())
    val profile: StateFlow<ConsultationProfile> = _profile.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    fun update(block: (ConsultationProfile) -> ConsultationProfile) {
        _profile.update(block)
    }

    fun save(context: Context) {
        ConsultationProfile.save(_profile.value)
        _saved.value = true
        CheckmateTTS.speak(context, "Profile saved. Generating your first smart plan.")
    }

    fun addBlockedSlot(slot: TimeSlot) = _profile.update { it.copy(blockedSlots = it.blockedSlots + slot) }
    fun removeBlockedSlot(index: Int)  = _profile.update { it.copy(blockedSlots = it.blockedSlots.toMutableList().also { l -> l.removeAt(index) }) }
}
