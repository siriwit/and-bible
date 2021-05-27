/*
 * Copyright (c) 2020 Martin Denham, Tuomas Airaksinen and the And Bible contributors.
 *
 * This file is part of And Bible (http://github.com/AndBible/and-bible).
 *
 * And Bible is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * And Bible is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with And Bible.
 * If not, see http://www.gnu.org/licenses/.
 *
 */
package net.bible.android.control.page

import android.util.Log
import net.bible.android.control.ApplicationScope
import net.bible.android.control.page.window.ActiveWindowPageManagerProvider
import net.bible.android.control.versification.Scripture
import net.bible.service.sword.SwordDocumentFacade
import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.versification.BibleBook
import javax.inject.Inject

/**
 * SesionFacade for CurrentPage used by View classes
 * @author Martin Denham [mjdenham at gmail dot com]
 */
@ApplicationScope
open class PageControl @Inject constructor(
	private val swordDocumentFacade: SwordDocumentFacade,
	private val activeWindowPageManagerProvider: ActiveWindowPageManagerProvider
) {

    /** This is only called after the very first bible download to attempt to ensure the first page is not 'Verse not found'
     * go through a list of default verses until one is found in the first/only book installed
     */
    fun setFirstUseDefaultVerse() {
        try {
            val versification = currentPageManager.currentBible.versification
            val defaultVerses = arrayOf(
                Verse(versification, BibleBook.JOHN, 3, 16),
                Verse(versification, BibleBook.GEN, 1, 1),
                Verse(versification, BibleBook.PS, 1, 1))
            val bibles = swordDocumentFacade.bibles
            if (bibles.size == 1) {
                val bible = bibles[0]
                for (verse in defaultVerses) {
                    if (bible.contains(verse)) {
                        currentPageManager.currentBible.doSetKey(verse)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Verse error")
        }
    }

    open val currentBibleVerse: Verse
        get() = currentPageManager.currentBible.singleKey

    /**
     * Return false if current page is not scripture, but only if the page is valid
     */
    val isCurrentPageScripture: Boolean
        get() {
            val currentVersePage = currentPageManager.currentVersePage
            val currentVersification = currentVersePage.versification
            val currentBibleBook = currentVersePage.currentBibleVerse.currentBibleBook
            val isCurrentBibleBookScripture = Scripture.isScripture(currentBibleBook)
            // Non-scriptural pages are not so safe.  They may be synched with the other screen but not support the current dc book
            return isCurrentBibleBookScripture ||
                !currentVersification.containsBook(currentBibleBook)
        }

    val currentPageManager: CurrentPageManager
        get() = activeWindowPageManagerProvider.activeWindowPageManager

    companion object {
        private const val TAG = "PageControl"
    }

}
