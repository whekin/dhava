import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const read = (path) =>
  readFile(new URL(`../dist/${path}`, import.meta.url), 'utf8');
for (const page of [
  'index.html',
  'privacy.html',
  'terms.html',
  'oauth/bikeyard/callback.html',
  '404.html',
]) {
  const html = await read(page);
  assert(html.includes('<title>'), `${page}: missing title`);
  assert(
    !/<script\b/i.test(html),
    `${page}: static pages must not ship JavaScript`,
  );
  assert(
    !html.includes('Preview:') || !process.env.NAKVALI_CONTACT_EMAIL,
    `${page}: contact not rendered`,
  );
}
const associations = JSON.parse(await read('.well-known/assetlinks.json'));
assert.equal(associations.length, 1);
assert.equal(associations[0].target.package_name, 'com.nakvali.app');
assert.deepEqual(associations[0].relation, [
  'delegate_permission/common.handle_all_urls',
]);
assert.equal(
  associations[0].target.sha256_cert_fingerprints.length,
  1,
  'Trust only the release certificate',
);
assert.match(
  associations[0].target.sha256_cert_fingerprints[0],
  /^(?:[A-F0-9]{2}:){31}[A-F0-9]{2}$/,
);
assert(
  (await read('oauth/bikeyard/callback.html')).includes('noindex, nofollow'),
);
assert(
  (await read('index.html')).includes(
    'https://github.com/whekin/dhava/releases',
  ),
);
console.log('Static routes, release link and Android association verified.');
