/*
 * The store front. Everything on the page comes from catalog.json plus the
 * per-build manifests it points at, so shipping a new app is a data change.
 */
(function () {
    'use strict';

    var root = document.getElementById('store-root');
    var state = {
        store: {},
        apps: [],
        query: '',
        platform: 'all'
    };

    /* ── Formatting ── */

    function esc(value) {
        return String(value == null ? '' : value).replace(/[&<>"']/g, function (character) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character];
        });
    }

    function formatSize(bytes) {
        var value = Number(bytes);
        if (!value || value < 0) {
            return '';
        }
        var units = ['B', 'KB', 'MB', 'GB'];
        var index = 0;
        while (value >= 1000 && index < units.length - 1) {
            value /= 1000;
            index++;
        }
        return (value >= 100 || index === 0 ? Math.round(value) : value.toFixed(1)) + ' ' + units[index];
    }

    function formatDate(value) {
        if (!value) {
            return '';
        }
        var date = new Date(value);
        if (isNaN(date.getTime())) {
            return '';
        }
        return date.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
    }

    function statusTone(status) {
        var value = String(status || '').toLowerCase();
        if (value.indexOf('live') === 0 || value.indexOf('ready') === 0) {
            return 'live';
        }
        if (value.indexOf('progress') > -1 || value.indexOf('beta') > -1) {
            return 'progress';
        }
        if (value.indexOf('planned') > -1 || value.indexOf('soon') > -1) {
            return 'planned';
        }
        return 'neutral';
    }

    function pill(text, tone) {
        return '<span class="pill pill--' + (tone || 'neutral') + '">' + esc(text) + '</span>';
    }

    /* ── Data ── */

    function loadCatalog() {
        return fetch('/catalog.json?t=' + Date.now())
            .then(function (response) {
                if (!response.ok) {
                    throw new Error('catalog ' + response.status);
                }
                return response.json();
            })
            .then(function (catalog) {
                state.store = catalog.store || {};
                state.apps = (catalog.apps || []).map(function (app) {
                    app.builds = app.builds || [];
                    return app;
                });
                return Promise.all(state.apps.map(hydrateApp));
            });
    }

    /*
     * A build either carries its own download URL or points at a manifest the
     * release workflow keeps current. Missing manifests are not fatal: the app
     * still lists, it just has nothing to hand out yet.
     */
    function hydrateApp(app) {
        return Promise.all(app.builds.map(function (build) {
            if (!build.manifest) {
                return Promise.resolve(build);
            }
            return fetch(build.manifest + '?t=' + Date.now())
                .then(function (response) {
                    return response.ok ? response.json() : null;
                })
                .catch(function () {
                    return null;
                })
                .then(function (manifest) {
                    if (manifest) {
                        build.version = manifest.version || build.version;
                        build.url = manifest.url || manifest.apk_url || build.url;
                        build.size = manifest.size_bytes || build.size;
                        build.released = manifest.released_at || build.released;
                        build.notes = manifest.release_notes || build.notes;
                    }
                    return build;
                });
        })).then(function () {
            return app;
        });
    }

    /*
     * An app is live when there is something to install, so the catalog only
     * carries a status when it means something else — "Beta", say.
     */
    function appStatus(app) {
        if (app.status) {
            return app.status;
        }
        var build = primaryBuild(app);
        return build && build.url ? 'Live' : 'Coming soon';
    }

    function primaryBuild(app) {
        var withUrl = app.builds.filter(function (build) {
            return build.url;
        });
        return withUrl[0] || app.builds[0] || null;
    }

    function appPlatforms(app) {
        return app.builds.map(function (build) {
            return build.platform;
        }).filter(Boolean);
    }

    function sortedApps() {
        return state.apps.slice().sort(function (first, second) {
            if (!!first.featured !== !!second.featured) {
                return first.featured ? -1 : 1;
            }
            return String(first.name).localeCompare(String(second.name), undefined, { sensitivity: 'base' });
        });
    }

    function visibleApps() {
        var query = state.query.trim().toLowerCase();
        return sortedApps().filter(function (app) {
            var platformMatch = state.platform === 'all' || appPlatforms(app).indexOf(state.platform) > -1;
            if (!platformMatch) {
                return false;
            }
            if (!query) {
                return true;
            }
            return [app.name, app.tagline, app.category, app.publisher, app.description]
                .concat(appPlatforms(app))
                .join(' ')
                .toLowerCase()
                .indexOf(query) > -1;
        });
    }

    /* ── Pieces ── */

    function iconMarkup(app) {
        if (app.icon) {
            return '<span class="app-icon"><img src="' + esc(app.icon) + '" alt="" loading="lazy" decoding="async"></span>';
        }
        return '<span class="app-icon"><span class="app-icon__monogram">' +
            esc(String(app.name || '?').charAt(0).toUpperCase()) + '</span></span>';
    }

    function accentStyle(app) {
        return app.accent ? ' style="--app-accent: ' + esc(app.accent) + '"' : '';
    }

    function getLabel(build) {
        if (!build) {
            return 'Coming soon';
        }
        if (!build.url) {
            return 'Coming soon';
        }
        return 'Get' + (build.platform ? ' for ' + build.platform : '');
    }

    function getButton(build, className, label) {
        var classes = 'button button--primary' + (className ? ' ' + className : '');
        if (!build || !build.url) {
            return '<span class="' + classes + '" aria-disabled="true">Coming soon</span>';
        }
        return '<a class="' + classes + '" href="' + esc(build.url) + '" download>' + esc(label || getLabel(build)) + '</a>';
    }

    function buildMeta(build, app) {
        if (!build) {
            return '';
        }
        return [app && app.price, build.platform, build.version ? 'v' + build.version : '', formatSize(build.size)]
            .filter(Boolean)
            .join(' · ');
    }

    function spotlightMarkup(app) {
        var build = primaryBuild(app);
        return '' +
            '<section class="spotlight"' + accentStyle(app) + '>' +
                '<div class="spotlight__art">' + iconMarkup(app) + '</div>' +
                '<div class="spotlight__text">' +
                    '<p class="spotlight__eyebrow">Featured app</p>' +
                    '<h2 class="spotlight__title">' + esc(app.name) + '</h2>' +
                    '<p class="spotlight__lead">' + esc(app.tagline || '') + '</p>' +
                    '<div class="spotlight__actions">' +
                        getButton(build) +
                        '<a class="button button--secondary" href="?app=' + encodeURIComponent(app.slug) + '">View details</a>' +
                    '</div>' +
                    (buildMeta(build, app) ? '<p class="detail-note">' + esc(buildMeta(build, app)) + '</p>' : '') +
                '</div>' +
            '</section>';
    }

    function cardMarkup(app) {
        var build = primaryBuild(app);
        return '' +
            '<article class="app-card">' +
                iconMarkup(app) +
                '<div class="app-card__body">' +
                    '<a class="app-card__title" href="?app=' + encodeURIComponent(app.slug) + '">' + esc(app.name) + '</a>' +
                    '<p class="app-card__publisher">' + esc(app.publisher || state.store.publisher || '') + '</p>' +
                    '<p class="app-card__meta">' + esc(buildMeta(build, app) || 'No build published yet') + '</p>' +
                '</div>' +
                '<div class="app-card__foot">' +
                    '<span class="app-card__pills">' +
                        (app.category ? pill(app.category, 'neutral') : '') +
                    '</span>' +
                    getButton(build, 'app-card__get', 'Get') +
                '</div>' +
            '</article>';
    }

    function placeholderCardMarkup() {
        return '' +
            '<article class="app-card app-card--placeholder">' +
                '<strong>More on the way</strong>' +
                '<p>Every new app from the ecosystem lands here first.</p>' +
            '</article>';
    }

    function toolbarMarkup() {
        var platforms = [];
        state.apps.forEach(function (app) {
            appPlatforms(app).forEach(function (platform) {
                if (platforms.indexOf(platform) === -1) {
                    platforms.push(platform);
                }
            });
        });
        platforms.sort();

        var chips = [{ value: 'all', label: 'All apps' }].concat(platforms.map(function (platform) {
            return { value: platform, label: platform };
        }));

        return '' +
            '<div class="store-toolbar">' +
                '<div class="store-search">' +
                    '<input class="store-search__input" type="search" id="store-search" placeholder="Search apps" ' +
                        'aria-label="Search apps" value="' + esc(state.query) + '">' +
                '</div>' +
                '<div class="store-filters" role="group" aria-label="Filter by platform">' +
                    chips.map(function (chip) {
                        var active = state.platform === chip.value;
                        return '<button type="button" class="chip' + (active ? ' is-active' : '') + '" ' +
                            'data-platform="' + esc(chip.value) + '" aria-pressed="' + active + '">' +
                            esc(chip.label) + '</button>';
                    }).join('') +
                '</div>' +
            '</div>';
    }

    /* ── Views ── */

    function renderCatalog() {
        var featured = sortedApps().filter(function (app) {
            return app.featured;
        })[0];

        document.title = 'Store — Harith Kavish';

        root.innerHTML = '' +
            '<div class="section-head">' +
                '<h1 class="section-head__title">Store</h1>' +
                '<p class="section-head__lead">' + esc(state.store.lead || '') + '</p>' +
            '</div>' +
            '<div id="store-spotlight">' + (featured ? spotlightMarkup(featured) : '') + '</div>' +
            toolbarMarkup() +
            '<div id="store-results"></div>';

        bindCatalogEvents();
        renderResults();
    }

    /* Only the results change while browsing, so the search field keeps focus. */
    function renderResults() {
        var apps = visibleApps();
        var isBrowsing = !state.query.trim() && state.platform === 'all';
        var spotlight = root.querySelector('#store-spotlight');
        var results = root.querySelector('#store-results');

        if (spotlight) {
            spotlight.hidden = !isBrowsing;
        }

        root.querySelectorAll('[data-platform]').forEach(function (chip) {
            var active = chip.dataset.platform === state.platform;
            chip.classList.toggle('is-active', active);
            chip.setAttribute('aria-pressed', String(active));
        });

        results.innerHTML = apps.length
            ? '<div class="app-grid">' + apps.map(cardMarkup).join('') + (isBrowsing ? placeholderCardMarkup() : '') + '</div>'
            : '<p class="store-empty">No apps match that search yet.</p>';
    }

    function bindCatalogEvents() {
        var search = root.querySelector('#store-search');
        if (search) {
            search.addEventListener('input', function (event) {
                state.query = event.target.value;
                renderResults();
            });
        }

        root.querySelectorAll('[data-platform]').forEach(function (chip) {
            chip.addEventListener('click', function () {
                state.platform = chip.dataset.platform;
                renderResults();
            });
        });
    }

    function detailPanels(app, build) {
        var panels = [];

        if (app.description) {
            panels.push(
                '<section class="detail-panel">' +
                    '<h2 class="detail-panel__title">About this app</h2>' +
                    '<p>' + esc(app.description) + '</p>' +
                '</section>'
            );
        }

        if ((app.features || []).length) {
            panels.push(
                '<section class="detail-panel">' +
                    '<h2 class="detail-panel__title">Features</h2>' +
                    '<ul class="feature-list">' +
                        app.features.map(function (feature) {
                            return '<li>' +
                                '<span class="feature-list__title">' + esc(feature.title) + '</span>' +
                                '<span class="feature-list__detail">' + esc(feature.detail || '') + '</span>' +
                            '</li>';
                        }).join('') +
                    '</ul>' +
                '</section>'
            );
        }

        if (build && build.notes) {
            panels.push(
                '<section class="detail-panel">' +
                    '<h2 class="detail-panel__title">What’s new' + (build.version ? ' in v' + esc(build.version) : '') + '</h2>' +
                    '<p class="release-notes">' + esc(build.notes) + '</p>' +
                '</section>'
            );
        }

        app.builds.forEach(function (item) {
            if (!(item.install || []).length) {
                return;
            }
            panels.push(
                '<section class="detail-panel">' +
                    '<h2 class="detail-panel__title">Install on ' + esc(item.platform || 'your device') + '</h2>' +
                    '<ol class="step-list">' +
                        item.install.map(function (step) {
                            return '<li>' + esc(step) + '</li>';
                        }).join('') +
                    '</ol>' +
                '</section>'
            );
        });

        return panels.join('');
    }

    function infoRow(term, value) {
        if (!value) {
            return '';
        }
        return '<div><dt>' + esc(term) + '</dt><dd>' + esc(value) + '</dd></div>';
    }

    function detailSidebar(app, build) {
        var info = '' +
            infoRow('Publisher', app.publisher || state.store.publisher) +
            infoRow('Price', app.price) +
            infoRow('Category', app.category) +
            infoRow('Status', appStatus(app)) +
            infoRow('Platforms', appPlatforms(app).join(', ')) +
            infoRow('Version', build && build.version ? 'v' + build.version : '') +
            infoRow('Size', build ? formatSize(build.size) : '') +
            infoRow('Updated', build ? formatDate(build.released) : '') +
            infoRow('Format', build && build.format ? build.format : '') +
            infoRow('Requires', build && build.requirements ? build.requirements : '');

        var links = (app.links || []).map(function (link) {
            return '<a href="' + esc(link.href) + '" target="_blank" rel="noopener">' + esc(link.label) + '</a>';
        }).join('');

        return '' +
            '<div class="detail-column">' +
                (info ? '<section class="detail-panel"><h2 class="detail-panel__title">Details</h2><dl class="info-list">' + info + '</dl></section>' : '') +
                (links ? '<section class="detail-panel"><h2 class="detail-panel__title">Links</h2><div class="link-list">' + links + '</div></section>' : '') +
            '</div>';
    }

    function renderDetail(app) {
        var build = primaryBuild(app);
        document.title = app.name + ' — Store — Harith Kavish';

        root.innerHTML = '' +
            '<a class="store-back" href="/">← All apps</a>' +
            '<section class="detail-head"' + accentStyle(app) + '>' +
                iconMarkup(app) +
                '<div class="detail-head__text">' +
                    '<p class="detail-head__eyebrow">' + esc(app.publisher || state.store.publisher || '') + '</p>' +
                    '<h1 class="detail-head__title">' + esc(app.name) + '</h1>' +
                    '<p class="detail-head__lead">' + esc(app.tagline || '') + '</p>' +
                    '<div class="detail-head__pills">' +
                        (app.category ? pill(app.category, 'neutral') : '') +
                        pill(appStatus(app), statusTone(appStatus(app))) +
                        appPlatforms(app).map(function (platform) {
                            return pill(platform, 'neutral');
                        }).join('') +
                    '</div>' +
                    '<div class="detail-head__actions">' +
                        app.builds.map(function (item) {
                            return getButton(item);
                        }).join('') +
                    '</div>' +
                    (buildMeta(build, app) ? '<p class="detail-note">' + esc(buildMeta(build, app)) +
                        (formatDate(build.released) ? ' · updated ' + esc(formatDate(build.released)) : '') + '</p>' : '') +
                '</div>' +
            '</section>' +
            '<div class="detail-grid">' +
                '<div class="detail-column">' + detailPanels(app, build) + '</div>' +
                detailSidebar(app, build) +
            '</div>';
    }

    function notFound(slug) {
        document.title = 'Not found — Store — Harith Kavish';
        root.innerHTML = '' +
            '<a class="store-back" href="/">← All apps</a>' +
            '<p class="store-empty">No app called “' + esc(slug) + '” lives in this store.</p>';
    }

    function render() {
        var slug = new URLSearchParams(window.location.search).get('app');
        if (!slug) {
            renderCatalog();
            return;
        }
        var app = state.apps.filter(function (item) {
            return item.slug === slug;
        })[0];
        if (app) {
            renderDetail(app);
        } else {
            notFound(slug);
        }
    }

    /* Detail views are query-string routes, so they stay shareable and go back. */
    function initRouting() {
        document.addEventListener('click', function (event) {
            var anchor = event.target.closest('a[href^="?app="], a.store-back');
            if (!anchor || event.metaKey || event.ctrlKey || event.shiftKey || event.button !== 0) {
                return;
            }
            event.preventDefault();
            history.pushState({}, '', anchor.getAttribute('href'));
            render();
            window.scrollTo({ top: 0, behavior: 'smooth' });
        });

        window.addEventListener('popstate', render);
    }

    initRouting();

    loadCatalog().then(render).catch(function () {
        root.innerHTML = '<p class="store-empty">The catalog could not be loaded. Please refresh and try again.</p>';
    });
})();
