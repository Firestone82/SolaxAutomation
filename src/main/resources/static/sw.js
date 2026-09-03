/* ============================================================================
   Service worker for the installed dashboard.

   It exists for one situation: the app is on a phone's home screen and the
   phone is not on the home network - at work, or with the Raspberry Pi down.
   Without it the app opens as the browser's offline page; with it, it opens
   as itself and says the application is unreachable, which is the truth and
   is readable.

   The data is never cached. Prices, the battery and what the modules are about
   to do are only worth having live, and a stale battery percentage presented as
   the current one would be worse than no number at all.
   ========================================================================= */

/*
   Bump this when a file in APP_SHELL changes: the new worker takes the new name,
   fills a fresh cache and deletes every older one on activation.
*/
const CACHE = 'solax-shell-v9';

const APP_SHELL = [
    './',
    'index.html',
    'assets/app.css',
    'assets/i18n.js',
    'assets/api.js',
    'assets/charts.js',
    'assets/app.js',
    'manifest.webmanifest',
    'icons/icon.svg',
    'icons/icon-192.png',
    'icons/icon-512.png'
];

self.addEventListener('install', event => {
    event.waitUntil(
        caches.open(CACHE)
            .then(cache => cache.addAll(APP_SHELL))
            // One missing file must not leave the app with no worker at all.
            .catch(error => console.warn('Shell not fully cached', error))
            .then(() => self.skipWaiting())
    );
});

self.addEventListener('activate', event => {
    event.waitUntil(
        caches.keys()
            .then(names => Promise.all(names
                .filter(name => name !== CACHE)
                .map(name => caches.delete(name))))
            .then(() => self.clients.claim())
    );
});

self.addEventListener('fetch', event => {
    const request = event.request;

    if (request.method !== 'GET') {
        return;
    }

    const url = new URL(request.url);

    // Another origin's, or the live data: neither is ours to answer from a cache.
    if (url.origin !== self.location.origin || url.pathname.startsWith('/api/')) {
        return;
    }

    /*
       A navigation is answered from the network when there is one, so a deployed change
       is picked up on the next visit, and from the cached page when there is not.
    */
    if (request.mode === 'navigate') {
        event.respondWith(
            fetch(request)
                .then(response => {
                    const copy = response.clone();
                    caches.open(CACHE).then(cache => cache.put('index.html', copy));
                    return response;
                })
                .catch(() => caches.match('index.html').then(cached => cached || Response.error()))
        );

        return;
    }

    /*
       Everything else - the stylesheet, the scripts, the icons - comes out of the cache
       straight away and is refreshed behind the page for the next load.
    */
    event.respondWith(
        caches.match(request).then(cached => {
            const network = fetch(request)
                .then(response => {
                    if (response.ok) {
                        const copy = response.clone();
                        caches.open(CACHE).then(cache => cache.put(request, copy));
                    }

                    return response;
                })
                .catch(() => cached);

            return cached || network;
        })
    );
});
