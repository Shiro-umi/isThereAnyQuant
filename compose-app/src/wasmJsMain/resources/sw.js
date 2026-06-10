/**
 * BigSmart Service Worker
 * 实现 PWA 离线访问和缓存策略
 */

// __CACHE_VERSION__ 占位符由构建脚本 injectCacheVersion() 在每次部署时替换。
// 这样 sw.js 内容会变 → 浏览器识别为新 SW → 触发 install/activate →
// 清掉旧的 STATIC_ASSETS 缓存（包括 index.html），用户无需手动 unregister。
const CACHE_NAME = 'bigsmart-app-cache-__CACHE_VERSION__';

// ⚠️ 字体缓存独立命名，不随 App 版本升级被清除
// 字体文件内容不变，可以永久缓存
const FONT_CACHE_NAME = 'bigsmart-fonts-v1';

// App 静态资源（随版本更新）
const STATIC_ASSETS = [
    '/',
    '/index.html',
    '/styles.css',
    '/manifest.json',
    '/brand-mark.svg',
    '/icon.svg'
];

// 字体资源（体积大，独立预缓存，永久有效）
const FONT_ASSETS = [
    '/composeResources/org.shiroumi.compose_app.generated.resources/font/NotoSansSC-Regular.ttf',
    '/composeResources/org.shiroumi.compose_app.generated.resources/font/NotoSansSC-Bold.ttf',
    '/composeResources/org.shiroumi.compose_app.generated.resources/font/NotoColorEmoji.ttf',
];

// 安装：缓存静态资源，后台预热字体缓存
self.addEventListener('install', (event) => {
    console.log('[Service Worker] Installing...');

    event.waitUntil(
        // 先缓存静态资源（阻塞安装完成）
        caches.open(CACHE_NAME)
            .then((cache) => {
                console.log('[Service Worker] Caching static assets');
                return cache.addAll(STATIC_ASSETS);
            })
            .then(() => {
                // 字体体积大，后台异步预热，不阻塞 SW 安装完成
                precacheFonts();
                console.log('[Service Worker] Skip waiting');
                return self.skipWaiting();
            })
            .catch((err) => {
                console.error('[Service Worker] Cache failed:', err);
            })
    );
});

/**
 * 后台预缓存字体
 * - 只缓存尚未缓存的字体（已有则跳过，避免重复下载）
 * - 不阻塞 SW 安装，静默在后台完成
 */
async function precacheFonts() {
    try {
        const fontCache = await caches.open(FONT_CACHE_NAME);
        for (const url of FONT_ASSETS) {
            const cached = await fontCache.match(url);
            if (!cached) {
                console.log('[Service Worker] Prefetching font:', url);
                const response = await fetch(url);
                if (response.ok) {
                    await fontCache.put(url, response);
                    console.log('[Service Worker] Font cached:', url);
                }
            } else {
                console.log('[Service Worker] Font already cached:', url);
            }
        }
    } catch (err) {
        console.warn('[Service Worker] Font precache failed (non-fatal):', err);
    }
}

// 激活：清理旧 App 缓存，但保留字体缓存
self.addEventListener('activate', (event) => {
    console.log('[Service Worker] Activating...');

    event.waitUntil(
        caches.keys()
            .then((cacheNames) => {
                return Promise.all(
                    cacheNames
                        // ✅ 保留字体缓存（FONT_CACHE_NAME），只清理旧版 App 缓存
                        .filter((name) => name !== CACHE_NAME && name !== FONT_CACHE_NAME)
                        .map((name) => {
                            console.log('[Service Worker] Deleting old cache:', name);
                            return caches.delete(name);
                        })
                );
            })
            .then(() => {
                console.log('[Service Worker] Claiming clients');
                return self.clients.claim();
            })
    );
});

// 拦截请求：按资源类型路由到不同缓存策略
self.addEventListener('fetch', (event) => {
    const { request } = event;
    const url = new URL(request.url);

    // 跳过非 GET 请求
    if (request.method !== 'GET') {
        return;
    }

    // 跳过浏览器扩展请求
    if (url.protocol === 'chrome-extension:' || url.protocol === 'moz-extension:') {
        return;
    }

    // 逃生通道：?freshsw=1 直接走网络，绕过所有缓存
    // 用法：用户在地址栏访问 https://xxx/?freshsw=1 即可强制取最新 html
    if (url.searchParams.get('freshsw') === '1') {
        return;
    }

    // 代码资源策略：
    // - compose-app.js：完全不走 SW。它由构建注入的 code version 决定 URL，
    //   且 webpack runtime 通过 document.currentScript 解析 publicPath，
    //   SW 介入会在新旧 SW 切换瞬间触发竞态（旧 SW 拦截新 hash 请求 → 缓存
    //   没有 → fetch 时被新 SW 抢占 controller → 失败）。
    // - .wasm：让 SW 缓存。Safari 的 HTTP 缓存对大文件（30MB+）不可靠，
    //   每次刷新都重新下载；SW Cache API 在 Safari 中对大文件稳定工作。
    //   wasm 文件名带 content hash，新旧版本 URL 不同，无竞态风险。
    if (isCodeAsset(url.pathname)) {
        return;
    }

    // .wasm 文件：SW 缓存优先（解决 Safari HTTP 缓存不持久问题）
    if (url.pathname.endsWith('.wasm')) {
        event.respondWith(cacheFirst(request));
        return;
    }

    // API / WebSocket 请求：网络优先，失败时返回缓存
    if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/ws/')) {
        event.respondWith(networkFirst(request));
        return;
    }

    // index.html / 根路径：必须 networkFirst，否则一次部署翻车后用户永远困在旧版
    // 这是 PWA 部署铁律：HTML 入口必须每次拿最新的，资源指纹靠 ?v= 参数
    if (url.pathname === '/' || url.pathname === '/index.html') {
        event.respondWith(networkFirst(request));
        return;
    }

    // 字体资源：专用字体缓存，永久缓存优先
    if (url.pathname.includes('/font/') && (
        url.pathname.endsWith('.ttf') ||
        url.pathname.endsWith('.otf') ||
        url.pathname.endsWith('.woff') ||
        url.pathname.endsWith('.woff2')
    )) {
        event.respondWith(fontCacheFirst(request));
        return;
    }

    // 其他静态资源：App 缓存优先（stale-while-revalidate）
    event.respondWith(cacheFirst(request));
});

/**
 * 判断是否是 compose-app.js（主脚本入口）。
 * compose-app.js 不走 SW，因为 webpack runtime 依赖 document.currentScript
 * 推断 publicPath，SW 介入会在新旧版本切换时触发竞态失败。
 * .wasm 文件虽然也是指纹化资源，但 Safari HTTP 缓存对大文件不可靠，
 * 需要 SW 介入缓存；wasm 文件名带 content hash，新旧版本 URL 不同，无竞态。
 */
function isCodeAsset(pathname) {
    return pathname === '/compose-app.js' || pathname.endsWith('/compose-app.js');
}

/**
 * 字体专用缓存策略
 * - 优先从字体缓存返回（命中率极高，字体内容不变）
 * - 未命中时从网络获取并写入字体缓存
 * - 不做后台更新（字体文件内容不会变）
 */
async function fontCacheFirst(request) {
    const fontCache = await caches.open(FONT_CACHE_NAME);
    const cached = await fontCache.match(request);

    if (cached) {
        console.log('[Service Worker] Font served from cache:', request.url);
        return cached;
    }

    // 字体缓存未命中（首次加载）
    console.log('[Service Worker] Font cache miss, fetching:', request.url);
    try {
        const response = await fetch(request);
        if (response.ok) {
            await fontCache.put(request, response.clone());
        }
        return response;
    } catch (err) {
        console.error('[Service Worker] Font fetch failed:', err);
        throw err;
    }
}

/**
 * App 资源缓存优先策略（stale-while-revalidate）
 * 先返回缓存，同时后台更新缓存。
 * 注意：网络失败时不再回退到 index.html —— 否则 JS/WASM 请求拿到 HTML 内容会被
 * 解析失败导致整个首屏挂掉。让错误自然抛回浏览器才能正确触发资源加载失败的处理。
 */
async function cacheFirst(request) {
    const cache = await caches.open(CACHE_NAME);
    const cached = await cache.match(request);

    if (cached) {
        // 后台更新缓存（stale-while-revalidate）
        fetch(request)
            .then((response) => {
                if (response.ok) {
                    cache.put(request, response.clone());
                }
            })
            .catch(() => {});

        return cached;
    }

    // 缓存未命中，请求网络
    const response = await fetch(request);
    if (response.ok) {
        cache.put(request, response.clone());
    }
    return response;
}

/**
 * 网络优先策略（API 接口）
 */
async function networkFirst(request) {
    const cache = await caches.open(CACHE_NAME);

    try {
        const networkResponse = await fetch(request);
        if (networkResponse.ok) {
            cache.put(request, networkResponse.clone());
        }
        return networkResponse;
    } catch (err) {
        console.log('[Service Worker] Network failed, trying cache');
        const cached = await cache.match(request);
        if (cached) {
            return cached;
        }
        throw err;
    }
}

// 后台同步
self.addEventListener('sync', (event) => {
    if (event.tag === 'sync-tasks') {
        event.waitUntil(syncTasks());
    }
});

async function syncTasks() {
    console.log('[Service Worker] Background sync');
}

// 推送通知
self.addEventListener('push', (event) => {
    const data = event.data.json();

    event.waitUntil(
        self.registration.showNotification(data.title, {
            body: data.body,
            icon: '/icon.svg',
            tag: data.tag,
            data: data.data
        })
    );
});

// 通知点击
self.addEventListener('notificationclick', (event) => {
    event.notification.close();

    event.waitUntil(
        self.clients.openWindow('/')
    );
});
