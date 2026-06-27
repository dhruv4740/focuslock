// Poll/evaluate cadence for the active-window monitor loop in agent.js.
// Policy, usage and focus-session changes arrive via Firestore realtime
// listeners, so no separate refresh intervals are needed here.
export const INTERVALS = {
  activeWindowPollMs: 4000,
  evaluateMs: 15000
};
