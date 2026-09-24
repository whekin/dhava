const contactEmail = import.meta.env.NAKVALI_CONTACT_EMAIL?.trim() ?? 'whekins@gmail.com';
if (contactEmail && !/^[^\s@<>]+@[^\s@<>]+$/.test(contactEmail)) {
  throw new Error(
    'NAKVALI_CONTACT_EMAIL must be a valid public contact address',
  );
}

export const site = {
  name: 'Nakvali',
  origin: 'https://nakvali.whekin.dev',
  repository: 'https://github.com/whekin/dhava',
  releases: 'https://github.com/whekin/dhava/releases/download/v0.1.0-test2/nakvali.apk',
  contactEmail,
  updated: '25 September 2026',
};
