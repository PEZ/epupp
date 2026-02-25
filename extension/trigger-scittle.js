// Ensure a default TrustedTypes policy for sites with strict Trusted Types CSP (e.g. YouTube).
// Must run before Scittle/Replicant renders anything.
if (window.trustedTypes && window.trustedTypes.createPolicy && !window.trustedTypes.defaultPolicy) {
  try {
    window.trustedTypes.createPolicy('default', {
      createHTML: function(s) { return s; },
      createScript: function(s) { return s; },
      createScriptURL: function(s) { return s; }
    });
  } catch(e) {
    console.warn('[Epupp] TrustedTypes default policy creation failed:', e.message);
  }
}

// Trigger Scittle to evaluate any x-scittle script tags in the DOM
if (window.scittle && window.scittle.core && window.scittle.core.eval_script_tags) {
  scittle.core.eval_script_tags();
}
