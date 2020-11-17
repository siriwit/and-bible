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
 */

import {enableVerseLongTouchSelectionMode} from "./highlighting";
import {registerVersePositions} from "./bibleview";
import {nextTick} from "./utils";
import Vue from "vue";
import BibleView from "../components/BibleView";
import $ from "jquery"

let currentScrollAnimation = null;
let contentReady = false;
export let toolbarOffset = 0;

export function setToolbarOffset(value, {doNotScroll = false, immediate = false} = {}) {
    console.log("setToolbarOffset", value, doNotScroll, immediate);
    const diff = toolbarOffset - value;
    toolbarOffset = value;
    const delay = immediate ? 0 : 500;

    if(diff !== 0 && !doNotScroll) {
        doScrolling(window.pageYOffset + diff, delay)
    }
}

export function updateLocation() {
    if(currentScrollAnimation == null) {
        jsInterface.onScroll(window.pageYOffset);
    }
}

export function stopScrolling() {
    if(currentScrollAnimation != null) {
        window.cancelAnimationFrame(currentScrollAnimation);
        currentScrollAnimation = null;
        console.log("Animation ends");
    }
}

export function doScrolling(elementY, duration) {
    console.log("doScrolling", elementY, duration);
    stopScrolling();
    const startingY = window.pageYOffset;
    const diff = elementY - startingY;
    let start;

    if(duration === 0) {
        window.scrollTo(0, elementY);
        return;
    }

    // Bootstrap our animation - it will get called right before next frame shall be rendered.
    console.log("Animation starts");
    currentScrollAnimation = window.requestAnimationFrame(function step(timestamp) {
        if (!start) start = timestamp;
        // Elapsed milliseconds since start of scrolling.
        const time = timestamp - start;
        // Get percent of completion in range [0, 1].
        const percent = Math.min(time / duration, 1);

        window.scrollTo(0, startingY + diff * percent);

        // Proceed with animation as long as we wanted it to.
        if (time < duration) {
            currentScrollAnimation = window.requestAnimationFrame(step);
        }
        else {
            updateLocation();
        }
    })
}

let lineSpacing = null;

function attributesToString(elem) {
    try {
        let result = "";
        for (const attr of elem.attributes) {
            result += `${attr.name}: ${attr.value}, `
        }
        return `[${elem.tagName} ${result} (${elem.innerText.slice(0, 50)}...)]`;
    } catch (e) {
        console.error("attributesToString fails", e);
        return `[${elem.tagName} (${elem.innerText.slice(0, 50)}...)]`;
    }
}

export function scrollToVerse(toId, now, delta = toolbarOffset) {
    console.log("scrollToVerse", toId, now, delta);
    stopScrolling();
    if(delta !== toolbarOffset) {
        toolbarOffset = delta;
    }
    const toElement = document.getElementById(toId) || document.getElementById("topOfBibleText");

    if (toElement != null) {
        const diff = toElement.offsetTop - window.pageYOffset;
        if(Math.abs(diff) > 800 / window.devicePixelRatio) {
            now = true;
        }
        console.log("Scrolling to", toElement, attributesToString(toElement), toElement.offsetTop - delta);
        const lineHeight = parseFloat(window.getComputedStyle(toElement).getPropertyValue('line-height'));
        if(lineSpacing != null) {
            const extra = (lineSpacing - 1) * 0.5;
            console.log(`Adding extra ${extra}`);
            delta += (lineHeight/lineSpacing) * extra;
        }
        if(now===true) {
            window.scrollTo(0, toElement.offsetTop - delta);
        }
        else {
            doScrolling(toElement.offsetTop - delta, 1000);
        }
    }
}

export function setDisplaySettings({marginLeft, marginRight, maxWidth, textColor, noiseOpacity, lineSpacing: lineSpacing_, justifyText, hyphenation} = {}, doNotReCalc = false) {
    lineSpacing = lineSpacing_ / 10;
    $(":root")
        .css("--max-width", `${maxWidth}mm`)
        .css("--text-color", textColor)
        .css("--hyphens", hyphenation ? "auto": "none")
        .css("--noise-opacity", noiseOpacity/100)
        .css("--line-spacing", `${lineSpacing}em`)
        .css("--text-align", justifyText? "justify" : "start");
    const content = $("#content")

    content
        .css("max-width", `${maxWidth}mm`)
        .css("hyphens", hyphenation ? "auto": "none")
        .css("text-align", justifyText? "justify" : "start");

    if(marginLeft || marginRight) {
        content
            .css('margin-left', `${marginLeft}mm`)
            .css('margin-right', `${marginRight}mm`)
    }

    $("body")
        .css("color", textColor)
        .css("line-height", `${lineSpacing}em`);

    if(!doNotReCalc) {
        registerVersePositions()
    }
}


export async function setupContent({jumpToChapterVerse, jumpToYOffsetRatio, toolBarOffset, displaySettings}  = {}) {
    console.log(`setupContent`, jumpToChapterVerse, jumpToYOffsetRatio, toolBarOffset, JSON.stringify(displaySettings));
    setDisplaySettings(displaySettings, true);
    const doScroll = jumpToYOffsetRatio != null && jumpToYOffsetRatio > 0;
    setToolbarOffset(toolBarOffset, {immediate: true, doNotScroll: !doScroll});

    await nextTick(); // Do scrolling only after view has been settled (fonts etc)

    $("#content").css('visibility', 'visible');

    if (jumpToChapterVerse != null) {
        scrollToVerse(jumpToChapterVerse, true);
        enableVerseLongTouchSelectionMode();
    } else if (doScroll) {
        console.log("jumpToYOffsetRatio", jumpToYOffsetRatio);
        const
            contentHeight = document.documentElement.scrollHeight,
            y = contentHeight * jumpToYOffsetRatio / window.devicePixelRatio;
        doScrolling(y, 0)
    } else {
        console.log("scrolling to beginning of document (now)");
        scrollToVerse(null, true);
    }

    await nextTick(); // set contentReady only after scrolling has been done

    registerVersePositions(true);

    contentReady = true;
    console.log("Content is set ready!");
    jsInterface.setContentReady();
    startVue();
}

function startVue() {
    console.log("Init vue");
    new Vue({
        el: "#vue",
        components: { BibleView },
        template: '<BibleView/>'
    });
    console.log("..finished");
}

export function hideContent() {
    $("#content").css('visibility', 'hidden');
}
