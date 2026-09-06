/**
 * Manual / guided accessibility checks — things no automated axe-core rule
 * can verify (it can confirm an alt attribute exists, not that its wording
 * is meaningful; it can't operate a screen reader or a keyboard). This list
 * is a static reference, not user data, so it lives in the frontend rather
 * than round-tripping through the backend — only the tester's answers
 * (see ManualChecksMap) are persisted per run.
 */
export interface ManualChecklistItem {
  id: string;
  label: string;
  description: string;
  wcagRef: string;
}

export const MANUAL_ACCESSIBILITY_CHECKLIST: ManualChecklistItem[] = [
  {
    id: 'keyboard-nav',
    label: 'Keyboard navigation',
    description: 'Tab through the entire page — every interactive element is reachable and operable without a mouse, with no trap.',
    wcagRef: '2.1.1, 2.1.2',
  },
  {
    id: 'focus-order-visible',
    label: 'Focus order & visibility',
    description: 'Focus order follows the logical/visual reading order, and a clearly visible focus indicator appears on every focused element.',
    wcagRef: '2.4.3, 2.4.7',
  },
  {
    id: 'screen-reader',
    label: 'Screen reader pass',
    description: 'Navigate the key user flows with a screen reader (NVDA, VoiceOver, or JAWS) — labels, roles, and state changes are announced correctly.',
    wcagRef: '4.1.2',
  },
  {
    id: 'meaningful-alt-text',
    label: 'Meaningful alt text',
    description: 'Image alt text conveys the same meaning or purpose as the image itself, not just that an attribute is present.',
    wcagRef: '1.1.1',
  },
  {
    id: 'contrast-in-context',
    label: 'Color contrast in context',
    description: 'Spot-check text and icon contrast against real backgrounds, including images, gradients, and hover/focus states axe cannot evaluate.',
    wcagRef: '1.4.3, 1.4.11',
  },
  {
    id: 'zoom-reflow',
    label: 'Zoom & reflow',
    description: 'The page stays usable at 200% and 400% browser zoom with no lost content and no horizontal scrolling.',
    wcagRef: '1.4.4, 1.4.10',
  },
  {
    id: 'form-errors',
    label: 'Form errors & instructions',
    description: 'Error messages are specific, programmatically associated with their field, and announced to assistive technology.',
    wcagRef: '3.3.1, 3.3.3',
  },
  {
    id: 'captions-transcripts',
    label: 'Captions & transcripts',
    description: 'Captions on video are accurate and in sync; transcripts are provided for audio-only content where required.',
    wcagRef: '1.2.2, 1.2.3',
  },
  {
    id: 'motion-control',
    label: 'Motion & animation control',
    description: 'Any auto-playing motion, animation, or carousel can be paused, stopped, or hidden by the user.',
    wcagRef: '2.2.2, 2.3.1',
  },
  {
    id: 'consistent-navigation',
    label: 'Consistent, predictable navigation',
    description: 'Navigation and repeated components (e.g. a "Search" link) are identified consistently across pages.',
    wcagRef: '3.2.3, 3.2.4',
  },
];
