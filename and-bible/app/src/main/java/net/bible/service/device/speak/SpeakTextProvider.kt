package net.bible.service.device.speak

import net.bible.service.format.osistohtml.osishandlers.SpeakCommand

interface SpeakTextProvider {
    fun isMoreTextToSpeak(): Boolean
    fun getNextSpeakCommand(): SpeakCommand
    fun getTotalChars(): Long
    fun getSpokenChars(): Long
    fun pause(fractionCompleted: Float)
    fun stop()
    fun rewind()
    fun forward()
    fun finishedUtterance(utteranceId: String)
    fun reset()
    fun persistState()
    fun restoreState(): Boolean
    fun clearPersistedState()
    fun prepareForContinue()
}
