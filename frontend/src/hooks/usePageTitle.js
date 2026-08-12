import { useEffect } from 'react';

const BASE_TITLE = 'DMARC Dashboard | Teknologiia';

/**
 * Sets the document title for as long as the page is mounted.
 *
 * @param title    what this page is, appended to the product name
 * @param options  `{ absolute: true }` uses the title exactly as given, with no
 *                 suffix. Needed by the public pages: a search engine renders the
 *                 application before reading the title, so whatever this hook sets
 *                 is what ends up in the search result — and a title assembled by
 *                 appending is longer than a result will show and buries the words
 *                 people actually search for behind the product name.
 */
export default function usePageTitle(title, options = {}) {
  const absolute = Boolean(options.absolute);

  useEffect(() => {
    const previousTitle = document.title;
    if (title && absolute) {
      document.title = title;
    } else {
      document.title = title ? `${title} — ${BASE_TITLE}` : BASE_TITLE;
    }
    return () => {
      document.title = previousTitle;
    };
  }, [title, absolute]);
}
