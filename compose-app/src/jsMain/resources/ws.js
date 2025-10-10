// 定义缓存的名称和版本
const CACHE_NAME = 'kmp-pwa-cache-v1';

// 定义需要缓存的核心文件列表
// 这些是你应用启动所必需的文件
const urlsToCache = [
    '.',
    'index.html',
    'manifest.json',
    'composeApp.js',
    'skiko.js',
    'skiko.wasm',
    // 'icon-192.png',
    // 'icon-512.png'
    // 注意: 如果你有其他重要的资源（如字体、其他图片），也需要加到这里
];

// 1. 安装 Service Worker
self.addEventListener('install', event => {
    // 执行安装步骤
    event.waitUntil(
        caches.open(CACHE_NAME)
            .then(cache => {
                console.log('Opened cache');
                return cache.addAll(urlsToCache);
            })
    );
});

// 2. 拦截网络请求
self.addEventListener('fetch', event => {
    event.respondWith(
        // 首先尝试从缓存中查找匹配的请求
        caches.match(event.request)
            .then(response => {
                // 如果缓存中存在，则直接返回缓存的响应
                if (response) {
                    return response;
                }
                // 如果缓存中不存在，则通过网络去请求
                return fetch(event.request);
            })
    );
});