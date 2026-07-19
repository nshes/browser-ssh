(function () {
    const initialTerminalElement = document.getElementById("ssh-terminal");
    if (!initialTerminalElement) {
        return;
    }

    const terminalFrame = initialTerminalElement.closest(".ssh-terminal-frame");
    const topConnectButtons = Array.from(document.querySelectorAll(".ssh-terminal-buttons [data-ssh-connect], .ssh-terminal-buttons #ssh-connect"));
    const duplicateButton = document.getElementById("ssh-duplicate");
    const disconnectButton = document.getElementById("ssh-disconnect");
    const uploadButton = document.getElementById("ssh-upload");
    const downloadButton = document.getElementById("ssh-download");
    const uploadInput = document.getElementById("ssh-upload-input");
    const initialOverlay = document.getElementById("ssh-overlay");
    const ready = initialTerminalElement.dataset.ready === "true";
    const autoConnect = initialTerminalElement.dataset.autoConnect === "true";
    let uploadDefaultPath = "/";
    let downloadDefaultPath = "/";
    const maxTransferBytes = Number(initialTerminalElement.dataset.maxTransferBytes || 104857600);
    const overlayTitle = initialOverlay?.querySelector(".ssh-overlay-content strong")?.textContent || "Browser SSH";
    const baseDocumentTitle = document.title || "Browser SSH";
    const maxTerminalPanes = 2;
    const heartbeatIntervalMs = 30000;
    const resizeDebounceMs = 120;
    const inputChunkCharacters = 4096;
    const inputChunkDelayMs = 8;
    const hangulFlushDelayMs = 350;
    const fileChunkBytes = 32 * 1024;
    const maxBufferedBytes = 2 * 1024 * 1024;
    const linkPattern = /\bhttps?:\/\/[^\s<>"'`\\]+/gi;
    const terminalFontFamily = "ui-monospace, SFMono-Regular, Menlo, Consolas, 'D2Coding', 'NanumGothicCoding', 'Noto Sans Mono CJK KR', monospace";
    const hangulInitials = ["ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ", "ㄹ", "ㅁ", "ㅂ", "ㅃ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"];
    const hangulVowels = ["ㅏ", "ㅐ", "ㅑ", "ㅒ", "ㅓ", "ㅔ", "ㅕ", "ㅖ", "ㅗ", "ㅘ", "ㅙ", "ㅚ", "ㅛ", "ㅜ", "ㅝ", "ㅞ", "ㅟ", "ㅠ", "ㅡ", "ㅢ", "ㅣ"];
    const hangulFinals = ["", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ", "ㄻ", "ㄼ", "ㄽ", "ㄾ", "ㄿ", "ㅀ", "ㅁ", "ㅂ", "ㅄ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"];
    const hangulInitialIndex = new Map(hangulInitials.map(function (value, index) { return [value, index]; }));
    const hangulVowelIndex = new Map(hangulVowels.map(function (value, index) { return [value, index]; }));
    const hangulFinalIndex = new Map(hangulFinals.map(function (value, index) { return [value, index]; }));
    const combinedHangulVowels = new Map([
        ["ㅗㅏ", "ㅘ"], ["ㅗㅐ", "ㅙ"], ["ㅗㅣ", "ㅚ"],
        ["ㅜㅓ", "ㅝ"], ["ㅜㅔ", "ㅞ"], ["ㅜㅣ", "ㅟ"],
        ["ㅡㅣ", "ㅢ"]
    ]);
    const combinedHangulFinals = new Map([
        ["ㄱㅅ", "ㄳ"], ["ㄴㅈ", "ㄵ"], ["ㄴㅎ", "ㄶ"],
        ["ㄹㄱ", "ㄺ"], ["ㄹㅁ", "ㄻ"], ["ㄹㅂ", "ㄼ"],
        ["ㄹㅅ", "ㄽ"], ["ㄹㅌ", "ㄾ"], ["ㄹㅍ", "ㄿ"],
        ["ㄹㅎ", "ㅀ"], ["ㅂㅅ", "ㅄ"]
    ]);

    let panes = [];
    let activePane = null;
    let nextPaneNumber = 1;
    let fileSocket = null;
    let uploadInProgress = false;
    let fileTransferInProgress = false;
    let visualViewportWatched = false;

    async function loadGatewayConfig() {
        try {
            const response = await window.fetch("/api/gateway/config", {
                credentials: "same-origin",
                headers: {"Accept": "application/json"}
            });
            if (!response.ok) {
                return;
            }
            const config = await response.json();
            if (typeof config.uploadPath === "string" && config.uploadPath.startsWith("/")) {
                uploadDefaultPath = config.uploadPath;
            }
            if (typeof config.downloadPath === "string" && config.downloadPath.startsWith("/")) {
                downloadDefaultPath = config.downloadPath;
            }
            if (typeof config.targetName === "string" && config.targetName.trim()) {
                const title = document.getElementById("ssh-target-title");
                if (title) {
                    title.textContent = "Browser SSH - " + config.targetName;
                }
                document.querySelectorAll("[data-ssh-target-name]").forEach(function (element) {
                    element.textContent = config.targetName;
                });
            }
        } catch (ignored) {
        }
    }

    function websocketUrl() {
        const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
        return protocol + "//" + window.location.host + "/ws/terminal";
    }

    function fileWebsocketUrl() {
        const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
        return protocol + "//" + window.location.host + "/ws/files";
    }

    function composeHangulSyllable(initial, vowel, finalConsonant) {
        const code = 0xac00 + ((initial * 21 + vowel) * 28 + finalConsonant);
        return String.fromCharCode(code);
    }

    function readHangulVowel(chars, index) {
        const first = chars[index];
        if (!hangulVowelIndex.has(first)) {
            return null;
        }
        const combined = index + 1 < chars.length
            ? combinedHangulVowels.get(first + chars[index + 1])
            : null;
        if (combined) {
            return {value: combined, length: 2};
        }
        return {value: first, length: 1};
    }

    function isHangulJamo(character) {
        return hangulInitialIndex.has(character)
            || hangulVowelIndex.has(character)
            || hangulFinalIndex.has(character);
    }

    function composeHangulBuffer(buffer, finalize) {
        const chars = Array.from(buffer);
        let output = "";
        let index = 0;

        while (index < chars.length) {
            const start = index;
            const initial = chars[index];
            if (hangulInitialIndex.has(initial)) {
                const vowel = readHangulVowel(chars, index + 1);
                if (vowel) {
                    let cursor = index + 1 + vowel.length;
                    if (cursor >= chars.length && !finalize) {
                        break;
                    }
                    let finalIndex = 0;
                    if (cursor < chars.length && hangulFinalIndex.has(chars[cursor])) {
                        const firstFinal = chars[cursor];
                        if (cursor + 1 >= chars.length) {
                            if (!finalize) {
                                break;
                            }
                            finalIndex = hangulFinalIndex.get(firstFinal);
                            cursor += 1;
                        } else if (hangulVowelIndex.has(chars[cursor + 1])) {
                            finalIndex = 0;
                        } else {
                            const combinedFinal = combinedHangulFinals.get(firstFinal + chars[cursor + 1]);
                            if (combinedFinal && (cursor + 2 >= chars.length || !hangulVowelIndex.has(chars[cursor + 2]))) {
                                if (cursor + 2 >= chars.length && !finalize) {
                                    break;
                                }
                                finalIndex = hangulFinalIndex.get(combinedFinal);
                                cursor += 2;
                            } else {
                                finalIndex = hangulFinalIndex.get(firstFinal);
                                cursor += 1;
                            }
                        }
                    }
                    output += composeHangulSyllable(
                        hangulInitialIndex.get(initial),
                        hangulVowelIndex.get(vowel.value),
                        finalIndex
                    );
                    index = cursor;
                    continue;
                }
            }

            const standaloneVowel = readHangulVowel(chars, index);
            if (standaloneVowel) {
                if (index + standaloneVowel.length >= chars.length && !finalize) {
                    break;
                }
                output += standaloneVowel.value;
                index += standaloneVowel.length;
                continue;
            }

            if (!finalize && start === chars.length - 1) {
                break;
            }
            output += initial;
            index += 1;
        }

        return {text: output.normalize("NFC"), rest: chars.slice(index).join("")};
    }

    function trimTerminalUrl(value) {
        let url = value || "";
        while (/[),.;:!?]+$/.test(url)) {
            url = url.slice(0, -1);
        }
        return url;
    }

    function openTerminalLink(url) {
        try {
            const parsed = new URL(url);
            if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
                return;
            }
        } catch (error) {
            return;
        }
        const opened = window.open(url, "_blank", "noopener,noreferrer");
        if (opened) {
            opened.opener = null;
        }
    }

    function activateTerminalLink(event, url, terminal) {
        if (!event || (!event.ctrlKey && !event.metaKey)) {
            terminal?.focus();
            return;
        }
        event.preventDefault?.();
        openTerminalLink(url);
        terminal?.clearSelection?.();
        terminal?.focus();
    }

    function setActivePane(pane) {
        if (!pane || !panes.includes(pane)) {
            return;
        }
        activePane = pane;
        panes.forEach(function (item) {
            item.root.classList.toggle("is-active", item === pane);
        });
        if (!document.hidden) {
            pane.clearTitleBadge();
        }
        updateGlobalControls();
    }

    function connectedPaneCount() {
        return panes.filter(function (pane) {
            return pane.isConnected();
        }).length;
    }

    function updateLayout() {
        terminalFrame.classList.toggle("is-split", panes.length > 1);
        panes.forEach(function (pane) {
            pane.setCloseEnabled(panes.length > 1);
            pane.fitAndResize();
        });
        updateGlobalControls();
    }

    function updateGlobalControls() {
        const allConnected = panes.length > 0 && panes.every(function (pane) {
            return pane.isConnected();
        });
        topConnectButtons.forEach(function (button) {
            button.disabled = !ready || allConnected;
        });
        if (disconnectButton) {
            disconnectButton.disabled = !activePane || !activePane.isConnected();
        }
        if (duplicateButton) {
            duplicateButton.disabled = !ready || panes.length >= maxTerminalPanes;
        }
        if (uploadButton) {
            uploadButton.disabled = !ready || uploadInProgress || fileTransferInProgress;
        }
        if (downloadButton) {
            downloadButton.disabled = !ready || uploadInProgress || fileTransferInProgress;
        }
    }

    function updateTitleBadge() {
        const badgeCount = panes.filter(function (pane) {
            return pane.hasTitleBadge;
        }).length;
        document.title = badgeCount > 0 ? "(" + badgeCount + ") " + baseDocumentTitle : baseDocumentTitle;
    }

    class TerminalPane {
        constructor(root, terminalElement, overlay, closeButton) {
            this.root = root;
            this.terminalElement = terminalElement;
            this.overlay = overlay;
            this.closeButton = closeButton;
            this.terminal = null;
            this.fitAddon = null;
            this.socket = null;
            this.heartbeatTimer = null;
            this.resizeTimer = null;
            this.resizeObserver = null;
            this.lastResize = null;
            this.inputFlushTimer = null;
            this.inputQueue = [];
            this.inputDrainCallbacks = [];
            this.hangulInputBuffer = "";
            this.hangulFlushTimer = null;
            this.hasTitleBadge = false;
            this.followTerminalOutput = true;
            this.scrollFrame = null;
            this.number = nextPaneNumber++;
            this.root.dataset.paneNumber = String(this.number);
            this.root.addEventListener("pointerdown", () => {
                setActivePane(this);
                this.clearTitleBadge();
            });
            if (this.closeButton) {
                this.closeButton.addEventListener("click", () => closePane(this));
            }
            this.root.querySelectorAll("[data-pane-connect]").forEach((button) => {
                button.addEventListener("click", () => {
                    this.connect();
                });
            });
        }

        setCloseEnabled(enabled) {
            if (this.closeButton) {
                this.closeButton.hidden = false;
                this.closeButton.disabled = !enabled;
            }
        }

        setConnected(connected) {
            if (this.overlay) {
                this.overlay.classList.toggle("hidden", connected);
            }
            this.root.classList.toggle("is-connected", connected);
            if (connected) {
                this.clearTitleBadge();
            }
            this.root.querySelectorAll("[data-pane-connect]").forEach((button) => {
                button.disabled = connected || !ready;
            });
            updateGlobalControls();
        }

        isConnected() {
            return this.socket && this.socket.readyState === WebSocket.OPEN;
        }

        markTitleBadge() {
            if (this.hasTitleBadge) {
                return;
            }
            this.hasTitleBadge = true;
            updateTitleBadge();
        }

        clearTitleBadge() {
            if (!this.hasTitleBadge) {
                return;
            }
            this.hasTitleBadge = false;
            updateTitleBadge();
        }

        scheduleScrollToBottom() {
            if (!this.terminal || !this.followTerminalOutput || this.scrollFrame !== null) {
                return;
            }
            this.scrollFrame = window.requestAnimationFrame(() => {
                this.scrollFrame = null;
                if (this.terminal && this.followTerminalOutput) {
                    this.terminal.scrollToBottom();
                }
            });
        }

        shouldMarkTitleBadge() {
            return document.hidden || activePane !== this || !document.hasFocus();
        }

        ensureTerminal() {
            if (this.terminal) {
                return this.terminal;
            }
            if (!window.Terminal) {
                window.console.warn("Terminal library failed to load.");
                return null;
            }
            this.terminal = new window.Terminal({
                cols: 100,
                rows: 24,
                cursorBlink: true,
                convertEol: true,
                fontFamily: terminalFontFamily,
                fontSize: 13,
                linkHandler: {
                    allowNonHttpProtocols: false,
                    activate: (event, url) => activateTerminalLink(event, url, this.terminal)
                },
                theme: {
                    background: "#111827",
                    black: "#9ca3af",
                    brightBlack: "#d1d5db",
                    foreground: "#e5e7eb",
                    cursor: "#ffffff"
                }
            });
            if (window.FitAddon && window.FitAddon.FitAddon) {
                this.fitAddon = new window.FitAddon.FitAddon();
                this.terminal.loadAddon(this.fitAddon);
            }
            this.terminal.open(this.terminalElement);
            this.configureTerminalInput();
            this.registerTerminalLinks();
            this.terminal.onScroll((position) => {
                const buffer = this.terminal?.buffer?.active;
                this.followTerminalOutput = !buffer || position >= buffer.baseY;
            });
            this.fitAndResize();
            this.terminal.onData((data) => this.sendInput(data));
            this.terminal.onResize((size) => this.sendResize(size.cols, size.rows));
            this.watchContainerResize();
            this.attachClipboardHandlers();
            return this.terminal;
        }

        configureTerminalInput() {
            const input = this.terminalElement.querySelector(".xterm-helper-textarea");
            if (!input) {
                return;
            }
            input.setAttribute("lang", "ko");
            input.setAttribute("inputmode", "text");
            input.setAttribute("autocomplete", "off");
            input.setAttribute("autocorrect", "off");
            input.setAttribute("autocapitalize", "off");
            input.setAttribute("spellcheck", "false");
            input.addEventListener("compositionstart", () => {
                this.terminalElement.classList.add("is-composing");
            });
            input.addEventListener("compositionend", () => {
                this.terminalElement.classList.remove("is-composing");
                this.terminal?.focus();
            });
            if (this.terminalElement.dataset.focusHandlers !== "true") {
                this.terminalElement.dataset.focusHandlers = "true";
                const focusTerminal = () => {
                    setActivePane(this);
                    this.clearTitleBadge();
                    this.terminal?.focus();
                };
                this.terminalElement.addEventListener("pointerdown", focusTerminal);
                this.terminalElement.addEventListener("touchend", focusTerminal, {passive: true});
            }
        }

        registerTerminalLinks() {
            if (!this.terminal || typeof this.terminal.registerLinkProvider !== "function" || this.terminalElement.dataset.linkProvider === "true") {
                return;
            }
            this.terminalElement.dataset.linkProvider = "true";
            this.terminal.registerLinkProvider({
                provideLinks: (line, callback) => {
                    const bufferLine = this.terminal.buffer?.active?.getLine(line - 1);
                    if (!bufferLine) {
                        callback([]);
                        return;
                    }
                    const text = bufferLine.translateToString(true);
                    const links = [];
                    let match;
                    linkPattern.lastIndex = 0;
                    while ((match = linkPattern.exec(text)) && links.length < 20) {
                        const url = trimTerminalUrl(match[0]);
                        if (!url) {
                            continue;
                        }
                        const startX = match.index + 1;
                        const endX = startX + url.length - 1;
                        links.push({
                            text: url,
                            range: {
                                start: {x: startX, y: line},
                                end: {x: endX, y: line}
                            },
                            activate: (event, textToOpen) =>
                                activateTerminalLink(event, textToOpen, this.terminal),
                            decorations: {
                                underline: true,
                                pointerCursor: true
                            }
                        });
                    }
                    callback(links);
                }
            });
        }

        fitAndResize() {
            if (this.fitAddon) {
                this.fitAddon.fit();
            }
            if (!this.fitAddon && this.terminal) {
                this.sendResize(this.terminal.cols, this.terminal.rows);
            }
        }

        scheduleFitAndResize() {
            if (this.resizeTimer) {
                window.clearTimeout(this.resizeTimer);
            }
            this.resizeTimer = window.setTimeout(() => {
                this.resizeTimer = null;
                this.fitAndResize();
            }, resizeDebounceMs);
        }

        watchContainerResize() {
            if (!this.resizeObserver && window.ResizeObserver) {
                this.resizeObserver = new window.ResizeObserver(() => this.scheduleFitAndResize());
                this.resizeObserver.observe(this.root);
                this.resizeObserver.observe(this.terminalElement);
            }
            window.addEventListener("resize", () => this.scheduleFitAndResize());
            if (!visualViewportWatched && window.visualViewport) {
                visualViewportWatched = true;
                window.visualViewport.addEventListener("resize", () => panes.forEach((pane) => pane.scheduleFitAndResize()));
                window.visualViewport.addEventListener("scroll", () => panes.forEach((pane) => pane.scheduleFitAndResize()));
            }
        }

        startHeartbeat() {
            this.stopHeartbeat();
            this.heartbeatTimer = window.setInterval(() => {
                if (this.socket && this.socket.readyState === WebSocket.OPEN) {
                    this.socket.send(JSON.stringify({type: "heartbeat"}));
                }
            }, heartbeatIntervalMs);
        }

        stopHeartbeat() {
            if (this.heartbeatTimer) {
                window.clearInterval(this.heartbeatTimer);
                this.heartbeatTimer = null;
            }
        }

        connect() {
            const term = this.ensureTerminal();
            if (!term || !ready || this.socket) {
                return;
            }
            setActivePane(this);
            term.clear();
            this.followTerminalOutput = true;
            this.scheduleScrollToBottom();
            this.setConnected(true);
            this.socket = new WebSocket(websocketUrl());

            this.socket.addEventListener("open", () => {
                this.fitAndResize();
                if (this.lastResize) {
                    this.sendResize(this.lastResize.cols, this.lastResize.rows);
                } else if (this.terminal) {
                    this.sendResize(this.terminal.cols, this.terminal.rows);
                }
                this.startHeartbeat();
                term.focus();
                updateGlobalControls();
            });

            this.socket.addEventListener("message", (event) => {
                const message = JSON.parse(event.data);
                if (message.type === "output") {
                    term.write(message.data, () => this.scheduleScrollToBottom());
                    if (this.shouldMarkTitleBadge()) {
                        this.markTitleBadge();
                    }
                } else if (message.type === "status") {
                    term.writeln("\r\n" + message.data, () => this.scheduleScrollToBottom());
                    if (this.shouldMarkTitleBadge()) {
                        this.markTitleBadge();
                    }
                } else if (message.type === "error") {
                    term.writeln("\r\n" + message.data);
                    this.markTitleBadge();
                } else if (message.type === "pong") {
                    return;
                }
            });

            this.socket.addEventListener("close", () => {
                this.stopHeartbeat();
                this.socket = null;
                this.inputQueue = [];
                this.inputDrainCallbacks = [];
                if (this.inputFlushTimer) {
                    window.clearTimeout(this.inputFlushTimer);
                    this.inputFlushTimer = null;
                }
                this.clearHangulInput();
                this.setConnected(false);
            });

            this.socket.addEventListener("error", () => {
                this.stopHeartbeat();
                this.clearHangulInput();
                this.setConnected(false);
            });
        }

        disconnect() {
            if (this.socket) {
                this.socket.close();
            }
        }

        close() {
            this.disconnect();
            this.stopHeartbeat();
            if (this.resizeObserver) {
                this.resizeObserver.disconnect();
                this.resizeObserver = null;
            }
            if (this.resizeTimer) {
                window.clearTimeout(this.resizeTimer);
                this.resizeTimer = null;
            }
            if (this.inputFlushTimer) {
                window.clearTimeout(this.inputFlushTimer);
                this.inputFlushTimer = null;
            }
            if (this.scrollFrame !== null) {
                window.cancelAnimationFrame(this.scrollFrame);
                this.scrollFrame = null;
            }
            this.clearHangulInput();
            this.clearTitleBadge();
            this.terminal?.dispose?.();
            this.root.remove();
        }

        isSocketOpen() {
            return this.socket && this.socket.readyState === WebSocket.OPEN;
        }

        runInputDrainCallbacks() {
            if (this.inputQueue.length || this.inputFlushTimer) {
                return;
            }
            const callbacks = this.inputDrainCallbacks;
            this.inputDrainCallbacks = [];
            callbacks.forEach(function (callback) {
                callback();
            });
        }

        flushInputQueue() {
            if (this.inputFlushTimer) {
                return;
            }
            if (!this.inputQueue.length) {
                this.runInputDrainCallbacks();
                return;
            }
            if (!this.isSocketOpen()) {
                this.inputQueue = [];
                this.inputDrainCallbacks = [];
                return;
            }
            const chunk = this.inputQueue.shift();
            this.socket.send(JSON.stringify({type: "input", data: chunk}));
            if (this.inputQueue.length) {
                this.inputFlushTimer = window.setTimeout(() => {
                    this.inputFlushTimer = null;
                    this.flushInputQueue();
                }, inputChunkDelayMs);
                return;
            }
            this.runInputDrainCallbacks();
        }

        onInputDrained(callback) {
            this.inputDrainCallbacks.push(callback);
            this.runInputDrainCallbacks();
        }

        sendRawInput(data) {
            if (!data || !this.isSocketOpen()) {
                return false;
            }
            for (let offset = 0; offset < data.length; offset += inputChunkCharacters) {
                this.inputQueue.push(data.slice(offset, offset + inputChunkCharacters));
            }
            this.flushInputQueue();
            return true;
        }

        flushHangulInput(finalize) {
            if (this.hangulFlushTimer) {
                window.clearTimeout(this.hangulFlushTimer);
                this.hangulFlushTimer = null;
            }
            if (!this.hangulInputBuffer) {
                return;
            }
            const composed = composeHangulBuffer(this.hangulInputBuffer, finalize);
            this.hangulInputBuffer = composed.rest;
            if (composed.text) {
                this.sendRawInput(composed.text);
            }
        }

        scheduleHangulFlush() {
            if (this.hangulFlushTimer) {
                window.clearTimeout(this.hangulFlushTimer);
            }
            this.hangulFlushTimer = window.setTimeout(() => {
                this.flushHangulInput(true);
            }, hangulFlushDelayMs);
        }

        clearHangulInput() {
            this.hangulInputBuffer = "";
            if (this.hangulFlushTimer) {
                window.clearTimeout(this.hangulFlushTimer);
                this.hangulFlushTimer = null;
            }
        }

        sendInput(data) {
            if (!data || !this.isSocketOpen()) {
                return false;
            }
            if (data === "\u007f" || data === "\b") {
                if (this.hangulInputBuffer) {
                    this.hangulInputBuffer = Array.from(this.hangulInputBuffer).slice(0, -1).join("");
                    this.scheduleHangulFlush();
                    return true;
                }
                return this.sendRawInput(data);
            }
            if (/[\x00-\x1f\x7f]/.test(data)) {
                this.flushHangulInput(true);
                return this.sendRawInput(data);
            }

            let sent = false;
            for (const character of Array.from(data.normalize("NFC"))) {
                if (isHangulJamo(character)) {
                    this.hangulInputBuffer += character;
                    this.flushHangulInput(false);
                    this.scheduleHangulFlush();
                    sent = true;
                    continue;
                }
                this.flushHangulInput(true);
                sent = this.sendRawInput(character) || sent;
            }
            return sent;
        }

        sendResize(cols, rows) {
            if (!cols || !rows) {
                return;
            }
            this.lastResize = {cols: cols, rows: rows};
            if (this.socket && this.socket.readyState === WebSocket.OPEN) {
                this.socket.send(JSON.stringify({type: "resize", cols: cols, rows: rows}));
            }
        }

        async copySelection() {
            if (!this.terminal || typeof this.terminal.hasSelection !== "function" || !this.terminal.hasSelection()) {
                return false;
            }
            const selection = this.terminal.getSelection();
            if (!selection) {
                return false;
            }
            try {
                await navigator.clipboard.writeText(selection);
                return true;
            } catch (error) {
                return false;
            }
        }

        async pasteClipboard() {
            try {
                const text = await navigator.clipboard.readText();
                if (text) {
                    this.pasteText(text);
                }
            } catch (error) {
                return;
            }
        }

        normalizePasteForTerminal(text) {
            return text.replace(/\r\n/g, "\r").replace(/\n/g, "\r");
        }

        pasteText(text) {
            if (!text || !this.terminal) {
                return false;
            }
            if (!this.isSocketOpen()) {
                this.terminal.focus();
                return false;
            }

            if (typeof this.terminal.paste === "function") {
                this.terminal.paste(text);
            } else {
                this.sendInput(this.normalizePasteForTerminal(text));
            }

            window.setTimeout(() => {
                this.onInputDrained(() => {
                    this.terminal.focus();
                });
            }, 0);
            return true;
        }

        attachClipboardHandlers() {
            if (this.terminalElement.dataset.clipboardHandlers === "true") {
                return;
            }
            this.terminalElement.dataset.clipboardHandlers = "true";
            this.terminalElement.addEventListener("contextmenu", async (event) => {
                event.preventDefault();
                setActivePane(this);
                this.clearTitleBadge();
                if (await this.copySelection()) {
                    this.terminal.clearSelection();
                    return;
                }
                await this.pasteClipboard();
            });
            this.terminalElement.addEventListener("mouseup", (event) => {
                if (event.button !== 0) {
                    return;
                }
                setActivePane(this);
                this.clearTitleBadge();
                window.setTimeout(() => this.copySelection(), 0);
            });
            this.terminalElement.addEventListener("paste", (event) => {
                event.preventDefault();
                event.stopPropagation();
                setActivePane(this);
                this.clearTitleBadge();
                const text = event.clipboardData?.getData("text/plain");
                if (text) {
                    this.pasteText(text);
                }
            }, true);
        }
    }

    function createPaneRoot() {
        const root = document.createElement("div");
        root.className = "ssh-terminal-pane";

        const closeButton = document.createElement("button");
        closeButton.className = "ssh-pane-close";
        closeButton.type = "button";
        closeButton.setAttribute("aria-label", "Close terminal pane");
        closeButton.textContent = "x";

        const terminal = document.createElement("div");
        terminal.className = "ssh-terminal";

        const overlay = document.createElement("div");
        overlay.className = "ssh-overlay";
        if (!ready) {
            overlay.classList.add("unavailable");
        }
        const overlayContent = document.createElement("div");
        overlayContent.className = "ssh-overlay-content";
        const title = document.createElement("strong");
        title.textContent = overlayTitle;
        const connectButton = document.createElement("button");
        connectButton.className = "button primary";
        connectButton.type = "button";
        connectButton.dataset.sshConnect = "";
        connectButton.dataset.paneConnect = "";
        connectButton.disabled = !ready;
        connectButton.textContent = "Connect";
        overlayContent.append(title, connectButton);
        overlay.appendChild(overlayContent);

        root.append(closeButton, terminal, overlay);
        terminalFrame.appendChild(root);
        return {root: root, terminal: terminal, overlay: overlay, closeButton: closeButton};
    }

    function closePane(pane) {
        if (panes.length <= 1) {
            return;
        }
        pane.close();
        panes = panes.filter(function (item) {
            return item !== pane;
        });
        if (activePane === pane) {
            activePane = panes[0] || null;
        }
        if (activePane) {
            setActivePane(activePane);
        }
        updateLayout();
    }

    function duplicatePane() {
        if (!ready || panes.length >= maxTerminalPanes) {
            return;
        }
        const created = createPaneRoot();
        const pane = new TerminalPane(created.root, created.terminal, created.overlay, created.closeButton);
        panes.push(pane);
        setActivePane(pane);
        updateLayout();
        pane.connect();
    }

    function setFileTransferBusy(busy, uploading) {
        fileTransferInProgress = busy;
        uploadInProgress = busy && uploading;
        updateGlobalControls();
    }

    function finishFileTransfer(message) {
        setFileTransferBusy(false, false);
        if (message) {
            window.alert(message);
        }
    }

    function safeFileName(fileName) {
        const safe = (fileName || "upload.bin").replace(/[\\/\r\n\u0000]+/g, "-").trim();
        return safe || "upload.bin";
    }

    function displayFileName(fileName) {
        return (fileName || "upload.bin").replace(/[\r\n\u0000]+/g, " ").trim() || "upload.bin";
    }

    function uploadRemotePath(promptPath, fileName) {
        const trimmed = (promptPath || "").trim();
        if (!trimmed) {
            return "";
        }
        if (trimmed.endsWith("/")) {
            return trimmed.replace(/\/+$/, "") + "/" + safeFileName(fileName);
        }
        return trimmed;
    }

    function displayBytes(bytes) {
        if (!Number.isFinite(bytes)) {
            return "";
        }
        if (bytes >= 1024 * 1024 * 1024) {
            return (bytes / 1024 / 1024 / 1024).toFixed(2).replace(/\.00$/, "") + " GiB";
        }
        if (bytes >= 1024 * 1024) {
            return Math.round(bytes / 1024 / 1024) + " MB";
        }
        if (bytes >= 1024) {
            return Math.round(bytes / 1024) + " KB";
        }
        return bytes + " B";
    }

    function closeFileSocket() {
        if (!fileSocket) {
            return;
        }
        const current = fileSocket;
        fileSocket = null;
        if (current.readyState === WebSocket.OPEN || current.readyState === WebSocket.CONNECTING) {
            current.close();
        }
    }

    function waitForSocketBackpressure(socketToWatch) {
        return new Promise(function (resolve) {
            const wait = function () {
                if (!socketToWatch || socketToWatch.readyState !== WebSocket.OPEN || socketToWatch.bufferedAmount <= maxBufferedBytes) {
                    resolve();
                    return;
                }
                window.setTimeout(wait, 50);
            };
            wait();
        });
    }

    async function sendUploadChunks(socketToUse, file) {
        let offset = 0;
        while (offset < file.size && socketToUse.readyState === WebSocket.OPEN) {
            await waitForSocketBackpressure(socketToUse);
            const nextOffset = Math.min(offset + fileChunkBytes, file.size);
            const chunk = await file.slice(offset, nextOffset).arrayBuffer();
            socketToUse.send(chunk);
            offset = nextOffset;
        }
        if (socketToUse.readyState === WebSocket.OPEN) {
            socketToUse.send(JSON.stringify({type: "upload-complete"}));
        }
    }

    function openFileSocket(onText, onBinary, onClose) {
        closeFileSocket();
        const nextSocket = new WebSocket(fileWebsocketUrl());
        nextSocket.binaryType = "arraybuffer";
        fileSocket = nextSocket;
        nextSocket.addEventListener("message", function (event) {
            if (typeof event.data === "string") {
                onText(JSON.parse(event.data), nextSocket);
            } else if (onBinary) {
                onBinary(event.data, nextSocket);
            }
        });
        nextSocket.addEventListener("close", function () {
            if (fileSocket === nextSocket) {
                fileSocket = null;
            }
            if (onClose) {
                onClose();
            }
        });
        nextSocket.addEventListener("error", function () {
            finishFileTransfer("Transfer failed");
        });
        return nextSocket;
    }

    function startUpload(file, remotePath) {
        if (!file || uploadInProgress || fileTransferInProgress) {
            return;
        }
        if (file.size > maxTransferBytes) {
            window.alert("Max " + displayBytes(maxTransferBytes));
            return;
        }
        setFileTransferBusy(true, true);
        openFileSocket(function (message, currentSocket) {
            if (message.type === "ready") {
                currentSocket.send(JSON.stringify({
                    type: "upload-start",
                    fileName: file.name,
                    sizeBytes: file.size,
                    remotePath: remotePath
                }));
                return;
            }
            if (message.type === "upload-ready") {
                sendUploadChunks(currentSocket, file);
                return;
            }
            if (message.type === "upload-progress") {
                return;
            }
            if (message.type === "upload-complete") {
                finishFileTransfer(message.data || "Upload complete");
                closeFileSocket();
                return;
            }
            if (message.type === "error") {
                finishFileTransfer(message.data || "Upload failed");
                closeFileSocket();
            }
        }, null, function () {
            if (uploadInProgress) {
                finishFileTransfer("Upload stopped");
            }
        });
    }

    async function startDownload(remotePath) {
        if (!remotePath || uploadInProgress || fileTransferInProgress) {
            return;
        }
        setFileTransferBusy(true, false);
        try {
            const response = await window.fetch("/api/files/inspect", {
                method: "POST",
                credentials: "same-origin",
                headers: {
                    "Accept": "application/json",
                    "Content-Type": "application/json"
                },
                body: JSON.stringify({remotePath: remotePath})
            });
            if (!response.ok) {
                throw new Error("Could not inspect the selected item.");
            }
            const inspection = await response.json();
            if (!inspection.allowed) {
                window.alert(
                    "This item is " + displayBytes(inspection.sizeBytes)
                    + ". The download limit is " + displayBytes(inspection.maxBytes) + "."
                );
                return;
            }
            const fileCount = inspection.kind === "directory"
                ? "\n" + Number(inspection.fileCount).toLocaleString() + " files (ZIP)"
                : "";
            const confirmed = window.confirm(
                "Download " + inspection.name + "?\n"
                + displayBytes(inspection.sizeBytes) + fileCount
                + "\n\nThe link expires in 2 minutes and can be used once."
            );
            if (!confirmed) {
                return;
            }
            if (typeof inspection.downloadUrl !== "string"
                    || !inspection.downloadUrl.startsWith("/api/files/download/")) {
                throw new Error("The download link is invalid.");
            }
            const link = document.createElement("a");
            link.href = inspection.downloadUrl;
            link.download = inspection.downloadName;
            link.hidden = true;
            document.body.appendChild(link);
            link.click();
            link.remove();
        } catch (error) {
            window.alert(error instanceof Error ? error.message : "Download failed.");
        } finally {
            setFileTransferBusy(false, false);
        }
    }

    function initializePrimaryPane() {
        let root = initialTerminalElement.closest(".ssh-terminal-pane");
        if (!root) {
            root = document.createElement("div");
            root.className = "ssh-terminal-pane";
            terminalFrame.insertBefore(root, initialTerminalElement);
            root.appendChild(initialTerminalElement);
            if (initialOverlay) {
                root.appendChild(initialOverlay);
            }
        }
        let closeButton = root.querySelector(".ssh-pane-close");
        if (!closeButton) {
            closeButton = document.createElement("button");
            closeButton.className = "ssh-pane-close";
            closeButton.type = "button";
            closeButton.setAttribute("aria-label", "Close terminal pane");
            root.insertBefore(closeButton, root.firstChild);
        }
        closeButton.textContent = "x";
        root.querySelectorAll("[data-ssh-connect]").forEach(function (button) {
            button.dataset.paneConnect = "";
        });
        const pane = new TerminalPane(root, initialTerminalElement, initialOverlay, closeButton);
        panes = [pane];
        setActivePane(pane);
        updateLayout();
        if (ready && autoConnect) {
            window.setTimeout(function () {
                pane.connect();
            }, 0);
        }
    }

    topConnectButtons.forEach(function (button) {
        button.addEventListener("click", function () {
            const target = activePane && !activePane.isConnected()
                ? activePane
                : panes.find(function (pane) { return !pane.isConnected(); }) || activePane;
            target?.connect();
        });
    });
    if (duplicateButton) {
        duplicateButton.addEventListener("click", function () {
            duplicatePane();
        });
    }
    if (disconnectButton) {
        disconnectButton.addEventListener("click", function () {
            activePane?.disconnect();
        });
    }
    if (uploadButton && uploadInput) {
        uploadButton.addEventListener("click", function () {
            if (uploadInProgress || fileTransferInProgress) {
                return;
            }
            uploadInput.value = "";
            uploadInput.click();
        });
        uploadInput.addEventListener("change", function () {
            const file = uploadInput.files && uploadInput.files[0];
            if (!file) {
                return;
            }
            const promptPath = window.prompt(
                "Upload \"" + displayFileName(file.name) + "\" (" + displayBytes(file.size) + ") to the path below",
                uploadDefaultPath
            );
            const remotePath = uploadRemotePath(promptPath, file.name);
            if (remotePath) {
                startUpload(file, remotePath);
            }
        });
    }
    if (downloadButton) {
        downloadButton.addEventListener("click", function () {
            if (uploadInProgress || fileTransferInProgress) {
                return;
            }
            const remotePath = window.prompt("Download item from the path below", downloadDefaultPath);
            if (remotePath) {
                startDownload(remotePath);
            }
        });
    }

    window.addEventListener("focus", function () {
        activePane?.clearTitleBadge();
    });
    document.addEventListener("visibilitychange", function () {
        if (!document.hidden) {
            activePane?.clearTitleBadge();
        }
    });

    loadGatewayConfig();
    initializePrimaryPane();
})();
