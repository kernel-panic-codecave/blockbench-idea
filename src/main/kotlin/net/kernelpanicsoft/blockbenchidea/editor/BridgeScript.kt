package net.kernelpanicsoft.blockbenchidea.editor

/**
 * A self-contained bridge script injected into every loaded Blockbench page.
 *
 * The marker `/*__INJECT__*/` is replaced at runtime with the `JBCefJSQuery`
 * invocation snippet, which routes JS messages to the plugin (see
 * [BlockbenchFileEditor]).
 *
 * The script:
 *  - polls until the Blockbench web app reports it is ready,
 *  - loads the model passed in via `__bbIdeaSetModel(name, content)`,
 *  - serializes the current project when requested by the native IntelliJ
 *    Save action through `__bbIdeaSave`,
 *  - hides Blockbench UI that the IDE already provides (the tab bar and the
 *    File menu's new/open entries),
 *  - periodically reports the unsaved-modified state of the current project.
 */
internal object BridgeScript {

    const val TEMPLATE: String = """
        (function () {
            'use strict';
            if (window['__bbIdeaBridgeInstalled']) return;
            window['__bbIdeaBridgeInstalled'] = true;

            window['__bbIdeaSend'] = function (msgObj) {
                try {
                    var text = String(msgObj.op) + '\n' + String(msgObj.payload == null ? '' : msgObj.payload);
                    var msg = text;
                    /*__INJECT__*/
                } catch (e) { if (window.console) console.error('bb-idea send:', e); }
            };

            window['__bbIdeaModelName'] = null;
            window['__bbIdeaModelData'] = null;

            function showProjectLoading() {
                if (!document.documentElement) return;
                var overlay = document.getElementById('bb-idea-project-loading');
                if (!overlay) {
                    overlay = document.createElement('div');
                    overlay.id = 'bb-idea-project-loading';
                    overlay.innerHTML =
                        '<div class="bb-idea-project-loading-spinner"></div>' +
                        '<div class="bb-idea-project-loading-label">Loading Blockbench project...</div>';
                    var style = document.createElement('style');
                    style.id = 'bb-idea-project-loading-style';
                    style.textContent =
                        '#bb-idea-project-loading {' +
                        ' position: fixed; inset: 0; z-index: 2147483647;' +
                        ' display: flex; flex-direction: column; align-items: center;' +
                        ' justify-content: center; gap: 14px;' +
                        ' background: var(--color-back, #181b1f);' +
                        ' color: var(--color-text, #cacad4);' +
                        ' font: 14px sans-serif;' +
                        ' transition: opacity 120ms ease;' +
                        '}' +
                        '.bb-idea-project-loading-spinner {' +
                        ' width: 28px; height: 28px; border-radius: 50%;' +
                        ' border: 3px solid var(--color-border, #3b3e49);' +
                        ' border-top-color: var(--color-accent, #3e90ff);' +
                        ' animation: bb-idea-project-loading-spin .8s linear infinite;' +
                        '}' +
                        '@keyframes bb-idea-project-loading-spin {' +
                        ' to { transform: rotate(360deg); }' +
                        '}';
                    (document.head || document.documentElement).appendChild(style);
                    document.documentElement.appendChild(overlay);
                }
                overlay.style.display = 'flex';
                overlay.style.opacity = '1';
            }

            function hideProjectLoading() {
                var overlay = document.getElementById('bb-idea-project-loading');
                if (!overlay) return;
                overlay.style.opacity = '0';
                setTimeout(function () {
                    if (overlay && overlay.parentNode) overlay.parentNode.removeChild(overlay);
                }, 140);
            }

            showProjectLoading();

            function hideDownloadAppButton() {
                var button = document.getElementById('web_download_button');
                if (button) {
                    button.hidden = true;
                    button.style.setProperty('display', 'none', 'important');
                }
                if (!document.getElementById('bb-idea-hide-download-app')) {
                    var style = document.createElement('style');
                    style.id = 'bb-idea-hide-download-app';
                    style.textContent = '#web_download_button { display: none !important; }';
                    (document.head || document.documentElement).appendChild(style);
                }
            }

            function hideUnwantedInterface() {
                if (!document.getElementById('bb-idea-hide-ui')) {
                    var style = document.createElement('style');
                    style.id = 'bb-idea-hide-ui';
                    style.textContent =
                        '#tab_bar { display: none !important; }' +
                        '#title_bar_home_button { display: none !important; }';
                    (document.head || document.documentElement).appendChild(style);
                }
                try {
                    if (window.BarItems) {
                        ['open_model', 'open_from_link'].forEach(function (id) {
                            var action = window.BarItems[id];
                            if (action && typeof action === 'object') {
                                action.condition = function () { return false; };
                            }
                        });
                    }
                    var fileMenu = window.MenuBar && window.MenuBar.menus && window.MenuBar.menus.file;
                    if (fileMenu && typeof fileMenu.removeAction === 'function') {
                        ['new', 'open_model', 'open_from_link'].forEach(function (id) {
                            try { fileMenu.removeAction(id); } catch (e) {}
                        });
                    }
                } catch (e) {
                    if (window.console) console.warn('bb-idea hide interface:', e);
                }
            }

            function isReady() {
                if (!window.Blockbench) return false;
                var setup = Blockbench.setup_successful;
                if (setup !== true && setup !== null) return false;
                if (typeof window.loadModelFile !== 'function') return false;
                if (!window.Codecs || !window.Codecs.project) return false;
                return true;
            }

            window['__bbIdeaSetModel'] = function (name, content, path) {
                window['__bbIdeaModelName'] = name;
                window['__bbIdeaModelData'] = content;
                window['__bbIdeaFilePath'] = path || '';
                try { window['__bbIdeaTryLoadNow'](); } catch (e) {}
            };

            window['__bbIdeaTryLoadNow'] = function () {
                if (window['__bbIdeaModelData'] == null) return;
                if (!isReady()) return;
                var content = window['__bbIdeaModelData'];
                var name = window['__bbIdeaModelName'];
                window['__bbIdeaModelData'] = null;
                window['__bbIdeaModelName'] = null;
                try {
                    window.loadModelFile({path: name, name: name, content: content});
                    var p = window.Blockbench.Project;
                    if (p && p !== 0) {
                        p.save_path = window['__bbIdeaFilePath'] || p.save_path;
                        p.export_path = p.save_path;
                        p.saved = true;
                    }
                    hideProjectLoading();
                    window['__bbIdeaSend']({op: 'project_loaded', payload: ''});
                    return true;
                } catch (e) {
                    try { window['__bbIdeaSend']({op: 'error', payload: String(e)}); } catch (e2) {}
                    return false;
                }
            };

            function base64(value) {
                return btoa(unescape(encodeURIComponent(String(value))));
            }

            function utf8(value) {
                return decodeURIComponent(escape(atob(value)));
            }

            var pickerRequests = {};
            window['__bbIdeaFilePickerResult'] = function (id, raw) {
                var request = pickerRequests[id];
                if (!request) return;
                delete pickerRequests[id];
                var files = raw ? raw.split('\n').map(function (entry) {
                    var fields = entry.split('\t');
                    var name = utf8(fields[0]);
                    var path = utf8(fields[1]);
                    var data = fields[2];
                    var image = /\.(png|jpe?g|gif|bmp|tga|webp)$/i.test(name);
                    return {
                        name: name,
                        path: path,
                        content: image ? 'data:application/octet-stream;base64,' + data : utf8(data)
                    };
                }) : [];
                request(files);
            };

            function installImportBridge() {
                if (!window.Filesystem || typeof window.Filesystem.importFile !== 'function' ||
                    window['__bbIdeaImportInstalled']) return;
                window['__bbIdeaImportInstalled'] = true;
                window.Filesystem.importFile = function (options, callback) {
                    options = options || {};
                    var id = String(Date.now()) + '_' + String(Math.random()).slice(2);
                    pickerRequests[id] = callback || function () {};
                    if (window.console) console.log('bb-idea opening native file picker', options);
                    window['__bbIdeaSend']({
                        op: 'picker',
                        payload: [
                            id,
                            options.multiple ? '1' : '0',
                            base64(options.title || 'Select Blockbench file'),
                            base64((options.extensions || []).join(','))
                        ].join('\n')
                    });
                };
                if (window.Blockbench) {
                    window.Blockbench.import = window.Filesystem.importFile;
                    window.Blockbench.importFile = window.Filesystem.importFile;
                }
            }

            function installExportBridge() {
                if (!window.Filesystem || typeof window.Filesystem.exportFile !== 'function' ||
                    window['__bbIdeaExportInstalled']) return;
                window['__bbIdeaExportInstalled'] = true;
                var originalExport = window.Filesystem.exportFile;
                window.Filesystem.exportFile = function (options, callback) {
                    if (!options || options.custom_writer || options.content == null) {
                        return originalExport.apply(this, arguments);
                    }
                    var name = options.name || 'file';
                    var extensions = Array.isArray(options.extensions) ? options.extensions : [];
                    if (extensions.length) {
                        var expectedExtension = String(extensions[0]).replace(/^\./, '');
                        var nameExtension = name.indexOf('.') >= 0
                            ? name.substring(name.lastIndexOf('.') + 1)
                            : '';
                        if (!nameExtension || extensions.indexOf(nameExtension) < 0) {
                            if (nameExtension === 'bbmodel') {
                                name = name.substring(0, name.lastIndexOf('.')) + '.' + expectedExtension;
                            } else {
                                name += '.' + expectedExtension;
                            }
                        }
                    }
                    var startpath = options.startpath || '';
                    function send(content, kind) {
                        window['__bbIdeaSend']({
                            op: 'export',
                            payload: [
                                base64(name),
                                base64(startpath),
                                content,
                                kind
                            ].join('\n')
                        });
                        if (typeof callback === 'function') callback(name);
                    }
                    if (typeof options.content === 'string') {
                        if (options.content.indexOf('data:') === 0) {
                            send(options.content.substring(options.content.indexOf(',') + 1), 'binary');
                        } else {
                            send(base64(options.content), 'text');
                        }
                    } else if (options.content instanceof Blob) {
                        var reader = new FileReader();
                        reader.onload = function () {
                            var data = String(reader.result || '');
                            send(data.substring(data.indexOf(',') + 1), 'binary');
                        };
                        reader.readAsDataURL(options.content);
                    } else {
                        return originalExport.apply(this, arguments);
                    }
                };
                if (window.Blockbench) {
                    window.Blockbench.export = window.Filesystem.exportFile;
                    window.Blockbench.exportFile = window.Filesystem.exportFile;
                }
            }

            window['__bbIdeaSave'] = function () {
                try {
                    if (!window.Blockbench) return;
                    var p = window.Blockbench.Project;
                    if (!p || p === 0) return;
                    var codec = window.Codecs && window.Codecs.project;
                    if (!codec || typeof codec.compile !== 'function') {
                        codec = p.format && p.format.codec;
                    }
                    if (!codec) return;
                    var content = codec.compile();
                    window['__bbIdeaSend']({op: 'save', payload: String(content)});
                    var exportCodec = p.format && p.format.codec;
                    var exportExtension = exportCodec && exportCodec.extension;
                    if (exportCodec && exportCodec !== codec && exportExtension === 'json' &&
                        typeof exportCodec.compile === 'function' &&
                        window.Filesystem && typeof window.Filesystem.exportFile === 'function') {
                        var exportName = typeof exportCodec.fileName === 'function'
                            ? exportCodec.fileName()
                            : (p.name || 'model');
                        var exportContent = exportCodec.compile();
                        if (typeof exportContent !== 'string') {
                            exportContent = JSON.stringify(exportContent);
                        }
                        window.Filesystem.exportFile({
                            name: exportName,
                            extensions: ['json'],
                            startpath: p.export_path || p.save_path || '',
                            content: exportContent
                        });
                    }
                } catch (e) {
                    try { window['__bbIdeaSend']({op: 'error', payload: String(e)}); } catch (e2) {}
                }
            };

            window['__bbIdeaSaveResult'] = function (ok, message) {
                try {
                    if (window.Blockbench && window.Blockbench.Project && window.Blockbench.Project !== 0 && ok) {
                        window.Blockbench.Project.saved = true;
                    }
                    if (typeof Blockbench.showQuickMessage === 'function') {
                        Blockbench.showQuickMessage(String(message), 3000);
                    }
                } catch (e) {}
            };

            window['__bbIdeaApplyIdeColors'] = function (colors) {
                try {
                    if (!colors || !document.documentElement) return;
                    var selector = [
                        '#main_toolbar',
                        '#left_bar',
                        '#right_bar',
                        '#status_bar',
                        '#panel_selector_bar',
                        '#start_screen',
                        '#action_selector'
                    ].join(',');
                    var rules = [];
                    Object.keys(colors).forEach(function (key) {
                        if (key.indexOf('__bbIdea') !== 0 && colors[key]) {
                            var property = key === 'accent'
                                ? '--bb-idea-accent'
                                : '--color-' + key;
                            rules.push(property + ':' + colors[key]);
                        }
                    });
                    var style = document.getElementById('bb-idea-ui-colors');
                    if (!style) {
                        style = document.createElement('style');
                        style.id = 'bb-idea-ui-colors';
                        (document.head || document.documentElement).appendChild(style);
                    }
                    style.textContent = selector + '{' + rules.join(';') + '}';
                } catch (e) {
                    if (window.console) console.warn('bb-idea color scheme sync:', e);
                }
            };

            window['__bbIdeaPrepareNewProject'] = function (name, path) {
                try {
                    window.__bbIdeaPendingProject = {name: String(name || ''), path: String(path || '')};
                    if (typeof window.selectNoProject === 'function') {
                        window.selectNoProject();
                    } else if (typeof window.setStartScreen === 'function') {
                        window.setStartScreen(true);
                    }
                    hideProjectLoading();
                } catch (e) {
                    if (window.console) console.warn('bb-idea prepare project:', e);
                }
            };

            if (window.Blockbench && typeof window.Blockbench.on === 'function' &&
                !window.__bbIdeaNewProjectListener) {
                window.__bbIdeaNewProjectListener = window.Blockbench.on('new_project', function (event) {
                    var pending = window.__bbIdeaPendingProject;
                    if (!pending || !event || !event.project) return;
                    event.project.name = pending.name;
                    event.project.save_path = pending.path;
                    event.project.export_path = pending.path;
                    event.project.saved = false;
                    window.__bbIdeaPendingProject = null;
                });
            }

            function reportModified() {
                if (!window.Blockbench) return;
                var p = window.Blockbench.Project;
                var modified = !(p && p !== 0 && p.saved === true);
                window['__bbIdeaSend']({op: 'modified', payload: modified ? '1' : '0'});
            }

            function syncUserData() {
                try {
                    var settings = {};
                    for (var i = 0; i < localStorage.length; i++) {
                        var key = localStorage.key(i);
                        if (key != null) settings[key] = localStorage.getItem(key);
                    }
                    window['__bbIdeaSend']({op: 'settings', payload: JSON.stringify(settings)});
                    if (window.Plugins && Array.isArray(window.Plugins.installed) &&
                        window.Plugins.source_cache && typeof window.Plugins.source_cache.get === 'function') {
                        Promise.all(window.Plugins.installed.map(function (entry) {
                            if (!entry || !entry.id) return null;
                            return window.Plugins.source_cache.get(entry.id).then(function (source) {
                                if (source) window['__bbIdeaSend']({
                                    op: 'plugin',
                                    payload: String(entry.id) + '.js\n' + String(source)
                                });
                            });
                        })).catch(function (e) {
                            if (window.console) console.warn('bb-idea plugin sync:', e);
                        });
                    }
                } catch (e) {
                    if (window.console) console.warn('bb-idea settings sync:', e);
                }
            }

            window['__bbIdeaReportModified'] = reportModified;

            var attempts = 0;
            setInterval(function () {
                try {
                    if (window['__bbIdeaReadySent']) return;
                    if (!isReady()) {
                        attempts++;
                        if (attempts > 60 && window['__bbIdeaModelData'] != null) {
                            window['__bbIdeaModelData'] = null;
                            try { window['__bbIdeaSend']({op: 'error', payload: 'Blockbench did not finish loading in time'}); } catch (e) {}
                        }
                        return;
                    }
                    window['__bbIdeaReadySent'] = true;
                    window['__bbIdeaSend']({op: 'ready', payload: String(Blockbench.version || '')});
                    hideDownloadAppButton();
                    hideUnwantedInterface();
                    window['__bbIdeaTryLoadNow']();
                    installImportBridge();
                    installExportBridge();
                    syncUserData();
                    setInterval(function () { try { syncUserData(); } catch (e) {} }, 5000);
                    window.addEventListener('beforeunload', function () {
                        try { syncUserData(); } catch (e) {}
                    });
                    setInterval(function () { try { reportModified(); } catch (e) {} }, 1000);
                } catch (e) {}
            }, 400);
        })();
        /*
        Copyright (c) by the Blockbench team. This bridge does not ship any Blockbench code.
        */
    """
}