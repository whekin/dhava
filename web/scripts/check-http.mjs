import assert from 'node:assert/strict';

// Run against nginx, not Astro preview: these are deployment behavior checks.
const base = process.argv[2] ?? 'http://127.0.0.1:4322';
for (const path of [
  '/',
  '/privacy',
  '/terms',
  '/oauth/bikeyard/callback',
  '/healthz',
]) {
  const response = await fetch(new URL(path, base), { redirect: 'manual' });
  assert.equal(response.status, 200, `${path}: expected 200 without redirects`);
}
const association = await fetch(new URL('/.well-known/assetlinks.json', base), {
  redirect: 'manual',
});
assert.equal(association.status, 200);
assert.match(
  association.headers.get('content-type') ?? '',
  /^application\/json/,
);
assert.equal(
  (await association.json())[0].target.package_name,
  'com.nakvali.app',
);
const callback = await fetch(
  new URL(
    '/oauth/bikeyard/callback?code=synthetic-test-only&state=synthetic',
    base,
  ),
);
assert.equal(callback.headers.get('cache-control'), 'no-store');
assert.equal(callback.headers.get('referrer-policy'), 'no-referrer');
assert(
  !(await callback.text()).includes('synthetic-test-only'),
  'Callback must never reflect authorization codes',
);
for (const path of ['/missing-page', '/api/v1/me', '/api/v1/missing']) {
  const response = await fetch(new URL(path, base), { redirect: 'manual' });
  assert.equal(
    response.status,
    404,
    `${path}: web must not mask missing routes with 200`,
  );
}
console.log(
  'nginx routes, Android JSON, callback privacy headers and 404s verified.',
);
