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

package net.bible.android.view.activity.page.screen

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuPopupHelper
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.children
import kotlinx.android.synthetic.main.bibleframes.view.*
import net.bible.android.BibleApplication
import net.bible.android.activity.R
import net.bible.android.control.event.ABEventBus
import net.bible.android.control.event.passage.CurrentVerseChangedEvent
import net.bible.android.control.event.window.CurrentWindowChangedEvent
import net.bible.android.control.page.window.Window
import net.bible.android.control.page.window.Window.WindowOperation
import net.bible.android.control.page.window.WindowControl
import net.bible.android.database.SettingsBundle
import net.bible.android.view.activity.MainBibleActivityScope
import net.bible.android.view.activity.page.BibleView
import net.bible.android.view.activity.page.BibleViewFactory
import net.bible.android.view.activity.page.CommandPreference
import net.bible.android.view.activity.page.MainBibleActivity
import net.bible.android.view.activity.page.MainBibleActivity.Companion.mainBibleActivity
import net.bible.android.view.activity.page.OptionsMenuItemInterface
import net.bible.android.view.activity.page.Preference
import net.bible.android.view.activity.page.SubMenuPreference
import net.bible.android.view.activity.settings.TextDisplaySettingsActivity
import net.bible.android.view.activity.settings.getPrefItem
import net.bible.android.view.util.widget.WindowButtonWidget
import net.bible.service.common.CommonUtils
import net.bible.service.device.ScreenSettings
import org.crosswire.jsword.versification.BookName
import java.util.*
import javax.inject.Inject
import kotlin.collections.ArrayList
import kotlin.math.max

private val isSplitVertically get() = CommonUtils.isSplitVertically

@SuppressLint("ViewConstructor")
class BibleViewFrame(val window: Window, context: Context): FrameLayout(context)

@SuppressLint("ViewConstructor")
class AllBibleViewsContainer(
    private val windowControl: WindowControl,
    private val bibleViewFactory: BibleViewFactory
): FrameLayout(mainBibleActivity) {

    private val res = BibleApplication.application.resources

    private val windowSeparatorWidthPixels: Int = res.getDimensionPixelSize(R.dimen.window_separator_width)
    private val windowSeparatorTouchExpansionWidthPixels: Int = res.getDimensionPixelSize(R.dimen.window_separator_touch_expansion_width)

    private val bibleRefOverlayOffset: Int = res.getDimensionPixelSize(R.dimen.bible_ref_overlay_offset)

    private val bibleFrames: MutableList<BibleViewFrame> = ArrayList()
    private val windowRepository = windowControl.windowRepository
    private val isSingleWindow get () = !windowControl.isMultiWindow && windowRepository.minimisedWindows.isEmpty()
    private val hiddenAlpha get() = if(ScreenSettings.nightMode) HIDDEN_ALPHA_NIGHT else HIDDEN_ALPHA
    private val windowButtons: MutableList<WindowButtonWidget> = ArrayList()
    private val restoreButtonsList: MutableList<WindowButtonWidget> = ArrayList()
    private var bibleReferenceOverlay: TextView? = null
    private var buttonsVisible = true

    init {
        val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        inflater.inflate(R.layout.bibleframes, this, true)
        ABEventBus.getDefault().register(this)
    }

    fun destroy() {
        ABEventBus.getDefault().unregister(this)
    }

    fun update() {
        val isSplitVertically = isSplitVertically
        biblesLinearLayout.orientation = if (isSplitVertically) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        val windows = windowRepository.visibleWindows
        val windowIds = windows.map { it.id }

        removeBibleFrames()

        windowButtons.clear()

        for ((orderNum, window) in windows.withIndex()) {
            Log.d(TAG, "Layout screen " + window.id + " of " + windows.size)
            val bibleFrame = buildBibleFrame(window)
            addBibleFrame(bibleFrame)
        }

        addSeparators()
        if(bibleReferenceOverlay == null) addBibleReferenceOverlay()

        restoreButtonsList.clear()
        restoreButtons.removeAllViews()
        pinnedRestoreButtons.removeAllViews()

        if(!isSingleWindow) {
            addRestoreButtons()
        }

        resetTouchTimer()
        mainBibleActivity.resetSystemUi()
    }

    private fun addBibleFrame(frame: BibleViewFrame) {
        val windowWeight = max(frame.window.weight, 0.1F)
        val lp = if (isSplitVertically)
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, windowWeight)
        else
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, windowWeight)

        biblesLinearLayout.addView(frame, lp)
        bibleFrames.add(frame)
    }

    private fun removeRemovedViews(windowIds: List<Long>) {
        val windowIdSet = windowIds.toSet()
        for(frame in bibleFrames.filter { !windowIdSet.contains(it.window.id) }) {
            biblesLinearLayout.removeView(frame)
            bibleFrames.remove(frame)
        }
    }

    private fun removeBibleFrames() {
        biblesLinearLayout.removeAllViews()
        bibleFrames.clear()
    }

    private fun addSeparators() {
        val size = bibleFrames.size
        for(i in 0 until size - 1) {
            addSeparator(bibleFrames[i], bibleFrames[i+1])
        }
    }

    private fun addSeparator(bf1: BibleViewFrame, bf2: BibleViewFrame) {
        val separator = Separator(
            context = context,
            separatorWidth = windowSeparatorWidthPixels,
            parentLayout = biblesLinearLayout,
            window1 = bf1.window,
            window2 = bf2.window,
            numWindows = bibleFrames.size,
            isPortrait = isSplitVertically,
            windowControl = windowControl
        )

        addBottomOrRightSeparatorTouchExtension(isSplitVertically, bf1, separator)
        addTopOrLeftSeparatorTouchExtension(isSplitVertically, bf2, separator)

        val lp = if (isSplitVertically)
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, windowSeparatorWidthPixels, 0f)
        else
            LinearLayout.LayoutParams(windowSeparatorWidthPixels, ViewGroup.LayoutParams.MATCH_PARENT, 0f)

        val currentPos = biblesLinearLayout.children.indexOf(bf1)
        biblesLinearLayout.addView(separator, currentPos + 1, lp)
    }

    @SuppressLint("RtlHardcoded")
    private fun addBottomOrRightSeparatorTouchExtension(isPortrait: Boolean,
                                                        previousWindowLayout: BibleViewFrame,
                                                        separator: Separator) {
        // add first touch delegate to framelayout which extends the touch area, otherwise it is difficult to select the separator to move it
        val frameLayoutParamsSeparatorDelegate = if (isPortrait)
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, windowSeparatorTouchExpansionWidthPixels, Gravity.BOTTOM)
        else
            LayoutParams(windowSeparatorTouchExpansionWidthPixels, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT)
        previousWindowLayout.addView(separator.touchDelegateView1, frameLayoutParamsSeparatorDelegate)
        // separator will adjust layouts when dragged
        separator.view1LayoutParams = previousWindowLayout.layoutParams as LinearLayout.LayoutParams
    }

    @SuppressLint("RtlHardcoded")
    private fun addTopOrLeftSeparatorTouchExtension(isPortrait: Boolean,
                                                    currentWindowLayout: BibleViewFrame,
                                                    separator: Separator) {
        // add separator handle touch delegate to framelayout
        val frameLayoutParamsSeparatorDelegate = if (isPortrait)
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, windowSeparatorTouchExpansionWidthPixels, Gravity.TOP)
        else
            LayoutParams(windowSeparatorTouchExpansionWidthPixels, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT)
        currentWindowLayout.addView(separator.touchDelegateView2, frameLayoutParamsSeparatorDelegate)

        // separator will adjust layouts when dragged
        separator.view2LayoutParams = currentWindowLayout.layoutParams as LinearLayout.LayoutParams
    }

    val currentWindowIds get() = bibleFrames.map { it.window.id }

    private fun addRestoreButtons() {
        restoreButtonsContainer.translationY = -mainBibleActivity.bottomOffset2
        restoreButtonsContainer.translationX = -mainBibleActivity.rightOffset1

        val pinnedWindows = windowRepository.windows.filter { it.isPinMode }
        val nonPinnedWindows = windowRepository.windows.filter { !it.isPinMode }

        for (win in pinnedWindows) {
            Log.d(TAG, "Show restore button")
            val restoreButton = createRestoreButton(win)
            restoreButtonsList.add(restoreButton)
            pinnedRestoreButtons.addView(restoreButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        for (win in nonPinnedWindows) {
            Log.d(TAG, "Show restore button")
            val restoreButton = createRestoreButton(win)
            restoreButtonsList.add(restoreButton)
            restoreButtons.addView(restoreButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun addBibleReferenceOverlay() {
        bibleReferenceOverlay = TextView(mainBibleActivity).apply {
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {
                setBackgroundResource(R.drawable.bible_reference_overlay)
            }
            visibility = if(buttonsVisible && mainBibleActivity.fullScreen) View.VISIBLE else View.GONE
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setLines(1)
            gravity = Gravity.CENTER
            translationY = -bibleRefOverlayOffset.toFloat()
            text = try {mainBibleActivity.bibleOverlayText} catch (e: MainBibleActivity.KeyIsNull) {""}
            textSize = 18F
        }
        addView(bibleReferenceOverlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

    }

    private fun buildBibleFrame(window: Window): BibleViewFrame {
        val frame = BibleViewFrame(window, mainBibleActivity)
        val bibleView = getCleanView(window)
        bibleView.updateBackgroundColor()

        frame.addView(
            bibleView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val defaultWindowActionButton =
            if (isSingleWindow) {
                createSingleWindowButton(window)
            } else if (window.defaultOperation == WindowOperation.CLOSE) {
                createCloseButton(window)
            } else {
                createMinimiseButton(window)
            }

        if (!isSplitVertically) {
            defaultWindowActionButton.translationY = mainBibleActivity.topOffset2
            if (windowRepository.lastVisibleWindow.id == window.id) {
                defaultWindowActionButton.translationX = -mainBibleActivity.rightOffset1
            }
        } else {
            if (windowRepository.firstVisibleWindow.id == window.id) {
                defaultWindowActionButton.translationY =
                    if (isSingleWindow) -mainBibleActivity.bottomOffset2
                    else mainBibleActivity.topOffset2
            }
            defaultWindowActionButton.translationX = -mainBibleActivity.rightOffset1
        }


        windowButtons.add(defaultWindowActionButton)
        frame.addView(defaultWindowActionButton,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                if (isSingleWindow) Gravity.BOTTOM or Gravity.RIGHT else Gravity.TOP or Gravity.RIGHT))
        return frame
    }

    fun onEvent(event: MainBibleActivity.FullScreenEvent) {
        toggleWindowButtonVisibility(true, force=true)
        resetTouchTimer()
    }

    fun onEvent(event: CurrentVerseChangedEvent) {
        updateBibleReference()
        if (event.window != null) updateMinimizedButtonLetter(event.window)
    }

    private fun updateBibleReference() {
        if(bibleReferenceOverlay == null) return

        mainBibleActivity.runOnUiThread {
            try {
                bibleReferenceOverlay!!.text = mainBibleActivity.bibleOverlayText
            } catch(e: MainBibleActivity.KeyIsNull) {
                Log.e(TAG, "Key is null, can't update", e)
            }
        }
    }

    private fun updateMinimizedButtonLetter(w: Window) {
        restoreButtonsList.find { it.window?.id == w.id }?.text = getDocumentInitial(w)
    }

    fun onEvent(event: MainBibleActivity.ConfigurationChanged) {
        toggleWindowButtonVisibility(true, force=true)
        resetTouchTimer()
    }

    fun onEvent(event: MainBibleActivity.TransportBarVisibilityChanged) {
        toggleWindowButtonVisibility(true, force=true)
        resetTouchTimer()
    }

    fun onEvent(event: CurrentWindowChangedEvent) {
        toggleWindowButtonVisibility(true, force=true)
        resetTouchTimer()
        updateBibleReference()
    }

    private var sleepTimer: Timer = Timer("TTS sleep timer")
    private var timerTask: TimerTask? = null

    fun onEvent(event: BibleView.BibleViewTouched) {
        resetTouchTimer()
    }

    private fun resetTouchTimer() {
        toggleWindowButtonVisibility(true)
        timerTask?.cancel()

        timerTask = object : TimerTask() {
            override fun run() {
                toggleWindowButtonVisibility(false)
            }
        }
        sleepTimer.schedule(timerTask, 2000L)
    }

    private fun toggleWindowButtonVisibility(show: Boolean, force: Boolean = false) {
//        if(restoreButtonContainer == null) {
//            // Too early to do anything
//            return
//        }
        if(buttonsVisible == show && !force) {
            return
        }
        mainBibleActivity.runOnUiThread {
            for ((idx, b) in windowButtons.withIndex()) {
                b.animate().apply {
                    // When switching to/from fullscreen, take into account the toolbar offset.
                    translationY(
                        if (isSingleWindow) -mainBibleActivity.bottomOffset2
                        else (
                            if(CommonUtils.isSplitVertically) {
                                if(idx == 0)
                                    mainBibleActivity.topOffset2
                                else 0.0F
                            }
                            else mainBibleActivity.topOffset2
                            )
                    )

                    if(show) {
                        alpha(VISIBLE_ALPHA)
                        interpolator = DecelerateInterpolator()
                    }  else {
                        alpha(hiddenAlpha)
                        interpolator = AccelerateInterpolator()
                    }
                    start()
                }
            }

            updateRestoreButtons(show)
            updateBibleReferenceOverlay(show)
        }
        buttonsVisible = show
    }

    private fun updateRestoreButtons(show: Boolean) {
        if(show) {
            restoreButtonsContainer.visibility = View.VISIBLE
            restoreButtonsContainer.animate()
                .alpha(VISIBLE_ALPHA)
                .translationY(-mainBibleActivity.bottomOffset2)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }  else {
            if(mainBibleActivity.fullScreen) {
                restoreButtonsContainer.animate().alpha(hiddenAlpha)
                    .setInterpolator(AccelerateInterpolator())
                    .start()
            }
        }
    }

    private fun updateBibleReferenceOverlay(_show: Boolean) {
        val show = mainBibleActivity.fullScreen && _show
        if(show) {
            bibleReferenceOverlay!!.visibility = View.VISIBLE
            bibleReferenceOverlay!!.animate().alpha(1.0f)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }  else {
            bibleReferenceOverlay!!.animate().alpha(0f)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { bibleReferenceOverlay!!.visibility = View.GONE }
                .start()
        }
    }

    /**
     * Attempt to fix occasional error: "The specified child already has a parent. You must call removeView() on the child's parent first."
     */
    private fun getCleanView(window: Window): BibleView {
        val bibleView = getView(window)
        val parent = bibleView.parent
        if (parent != null && parent is ViewGroup) {
            parent.removeView(bibleView)
        }
        return bibleView
    }

    fun getView(window: Window): BibleView {
        return bibleViewFactory.getOrCreateBibleView(window)
    }

    private fun createSingleWindowButton(window: Window): WindowButtonWidget {
        return createTextButton("⊕",
            { v -> windowControl.addNewWindow()},
            { v -> false},
            window
        )
    }

    private fun createCloseButton(window: Window): WindowButtonWidget {
        return createTextButton("X",
            { v -> showPopupWindow(window, v)},
            { v -> windowControl.closeWindow(window); true},
            window
        )
    }

    private fun createMinimiseButton(window: Window): WindowButtonWidget {
        val text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) "☰" else "━━"
        return createTextButton(text,
            { v -> showPopupWindow(window, v) },
            { v -> windowControl.minimiseWindow(window); true },
            window
        )
    }

    private fun createRestoreButton(window: Window): WindowButtonWidget {
        return WindowButtonWidget(window, windowControl,true, mainBibleActivity).apply {
            text = getDocumentInitial(window)
            setOnClickListener { windowControl.restoreWindow(window) }
            setOnLongClickListener { v-> showPopupWindow(window, v); true }
        }
    }

    /**
     * Get the first initial of the doc in the window to show in the minimise restore button
     */
    private fun getDocumentInitial(window: Window): String {
        return try {
            val abbrv = window.pageManager.currentPage.currentDocument?.abbreviation
            return abbrv ?: ""
            //abbrv?.substring(0, 1) ?: ""
        } catch (e: Exception) {
            " "
        }

    }

    private fun createTextButton(text: String, onClickListener: (View) -> Unit,
                                 onLongClickListener: ((View) -> Boolean)? = null,
                                 window: Window?): WindowButtonWidget {
        return WindowButtonWidget(window, windowControl, false, mainBibleActivity).apply {
            this.text = text
            setOnClickListener(onClickListener)
            setOnLongClickListener(onLongClickListener)
        }
    }

    private fun handlePrefItem(window: Window, item: MenuItem) {
        val itemOptions = getItemOptions(window, item)
        if(itemOptions is SubMenuPreference)
            return

        if(itemOptions.isBoolean) {
            itemOptions.value = !(itemOptions.value == true)
            itemOptions.handle()
            item.isChecked = itemOptions.value == true
        } else {
            val onReady = {
                if(itemOptions.requiresReload) {
                    window.updateText()
                } else {
                    window.bibleView?.updateTextDisplaySettings()
                }
                Unit
            }
            itemOptions.openDialog(mainBibleActivity, {onReady()}, onReady)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun showPopupWindow(window: Window, view: View) {
        // ensure actions affect the right window
        timerTask?.cancel()
        toggleWindowButtonVisibility(true)
        if(window.isVisible) {
            windowControl.activeWindow = window
        }

        val popup = PopupMenu(mainBibleActivity, view)
        popup.setOnMenuItemClickListener { menuItem ->
            resetTouchTimer()
            handlePrefItem(window, menuItem)
            true
        }

        val inflater = popup.menuInflater
        val menu = popup.menu
        inflater.inflate(R.menu.window_popup_menu, menu)

        val moveWindowsSubMenu = menu.findItem(R.id.moveWindowSubMenu).subMenu

        moveWindowsSubMenu.removeItem(R.id.moveItem)

        var count = 0

        val oldValue = BookName.isFullBookName()

        BookName.setFullBookName(false)
        val thisIdx = windowControl.windowRepository.windowList.indexOf(window)
        windowControl.windowRepository.windowList.forEach {
            if(it.id != window.id) {
                val p = it.pageManager.currentPage
                val title = BibleApplication.application.getString(R.string.move_window_to_position, "${count + 1} (${p.currentDocument?.abbreviation}: ${p.key?.name})")
                val item = moveWindowsSubMenu.add(Menu.NONE, R.id.moveItem, count, title)
                item.setIcon(if (thisIdx > count) R.drawable.ic_arrow_drop_up_grey_24dp else R.drawable.ic_arrow_drop_down_grey_24dp)
            }
            count ++;
        }
        BookName.setFullBookName(oldValue)

        val textOptionsSubMenu = menu.findItem(R.id.textOptionsSubMenu).subMenu
        textOptionsSubMenu.removeItem(R.id.textOptionItem)

        val lastSettings = CommonUtils.lastDisplaySettings
        if(lastSettings.isNotEmpty()) {
            for ((idx, t) in lastSettings.withIndex()) {
                textOptionsSubMenu.add(Menu.NONE, R.id.textOptionItem, idx, t.name)
            }
        } else {
            menu.removeItem(R.id.textOptionsSubMenu)
            val item = menu.add(Menu.NONE, R.id.allTextOptions, 1000, R.string.all_text_options_window_menutitle_alone)
            item.setIcon(R.drawable.ic_text_format_white_24dp)
        }


        fun handleMenu(menu: Menu) {
            for(item in menu.children) {
                val itmOptions = getItemOptions(window, item)
                if(itmOptions.title != null) {
                    item.title = itmOptions.title
                }
                item.isVisible = itmOptions.visible
                item.isEnabled = itmOptions.enabled
                item.isCheckable = itmOptions.isBoolean
                if(itmOptions is Preference) {
                    if (itmOptions.inherited) {
                        item.setIcon(R.drawable.ic_sync_white_24dp)
                    } else {
                        item.setIcon(R.drawable.ic_sync_disabled_green_24dp)
                    }
                }

                if(item.hasSubMenu()) {
                    handleMenu(item.subMenu)
                    continue;
                }

                item.isChecked = itmOptions.value == true
            }
        }
        handleMenu(menu)

        val menuHelper = MenuPopupHelper(mainBibleActivity, menu as MenuBuilder, view)
        menuHelper.setOnDismissListener {
            resetTouchTimer()
            mainBibleActivity.resetSystemUi()
        }

        menuHelper.setForceShowIcon(true)
        menuHelper.show()
    }

    private fun getItemOptions(window: Window, item: MenuItem): OptionsMenuItemInterface {
        val settingsBundle = SettingsBundle(
            windowId = window.id,
            pageManagerSettings = window.pageManager.textDisplaySettings,
            workspaceId = windowControl.windowRepository.id,
            workspaceName = windowControl.windowRepository.name,
            workspaceSettings = windowControl.windowRepository.textDisplaySettings
        )

        return when(item.itemId) {

            R.id.windowNew -> CommandPreference(
                launch = {_, _, _ -> windowControl.addNewWindow()},
                visible = !window.isLinksWindow && !window.isMinimised
            )
            R.id.windowSynchronise -> CommandPreference(
                handle = {windowControl.setSynchronised(window, !window.isSynchronised)},
                value = window.isSynchronised,
                visible = !window.isLinksWindow
            )
            R.id.pinMode -> CommandPreference(
                handle = {windowControl.setPinMode(window, !window.isPinMode)},
                value = window.isPinMode,
                visible = !window.isLinksWindow
            )
            R.id.moveWindowSubMenu -> SubMenuPreference(false,
                visible = !window.isLinksWindow
            )
            R.id.textOptionsSubMenu -> SubMenuPreference(
                onlyBibles = false,
                visible = window.isVisible
            )
            R.id.windowClose -> CommandPreference(
                launch = { _, _, _ ->  windowControl.closeWindow(window)},
                visible = windowControl.isWindowRemovable(window)
            )
            R.id.windowMinimise -> CommandPreference(
                launch = {_, _, _ -> windowControl.minimiseWindow(window)},
                visible = windowControl.isWindowMinimisable(window)
            )
            R.id.allTextOptions -> CommandPreference(
                launch = {_, _, _ ->
                    val intent = Intent(mainBibleActivity, TextDisplaySettingsActivity::class.java)
                    intent.putExtra("settingsBundle", settingsBundle.toJson())
                    mainBibleActivity.startActivityForResult(intent, MainBibleActivity.TEXT_DISPLAY_SETTINGS_CHANGED)
                },
                visible = window.isVisible
            )
            R.id.moveItem -> CommandPreference({_, _, _ ->
                windowControl.moveWindow(window, item.order)
                Log.d(TAG, "Number ${item.order}")
            },
                visible = !window.isLinksWindow
            )
            R.id.textOptionItem -> getPrefItem(settingsBundle, CommonUtils.lastDisplaySettings[item.order])

            else -> throw RuntimeException("Illegal menu item")
        }
    }

    companion object {
        private const val TAG = "BibleViewsContainer"
        private const val HIDDEN_ALPHA = 0.2F
        private const val HIDDEN_ALPHA_NIGHT = 0.5F
        private const val VISIBLE_ALPHA = 1.0F
    }

}

@MainBibleActivityScope
class DocumentWebViewBuilder @Inject constructor(
    private val windowControl: WindowControl,
    private val mainBibleActivity: MainBibleActivity,
    private val bibleViewFactory: BibleViewFactory
) {
    private var allBibleViewsContainer: AllBibleViewsContainer? = null

    @SuppressLint("RtlHardcoded")
    fun buildWebViews(): AllBibleViewsContainer {
        Log.d(TAG, "Layout web views")

        val topView = allBibleViewsContainer?: AllBibleViewsContainer(windowControl, bibleViewFactory).also {
            allBibleViewsContainer = it
        }

        allBibleViewsContainer!!.update()
        return topView
    }

    fun destroy() {
        allBibleViewsContainer?.destroy()
    }

    companion object {
        private const val TAG = "DocumentWebViewBuilder"
    }


}
