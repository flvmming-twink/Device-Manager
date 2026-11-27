# Device Manager 1.0.1 — independent finish review

Disposition: **fix**

Scope: native Android phone, Pixel 2 / API 28, 1080×1920; light and dark at font scale 1.0, dark at 1.3. Review date: 2026-11-27. This is a full review of the supplied capture set, not a verdict on the later correction batch. No app code was changed and no emulator was operated by the reviewer.

## persistence

PRODUCT.md records the operative truth: equipment CRUD, location, disposal status, trash, CSV, and two QR modes. It also preserves the user's equal priority for register and scanning. The surface contract records the technical-catalog direction and its delegated choice accurately; the comp sidecar does not pretend the user explicitly selected it. The visible implementation retains this product and direction.

DESIGN.md is a provisional, useful token-bearing account of the cobalt, open-row system. The final documentation handoff remains owed after corrections, including the machine-readable design sidecar. Document the actual native filter chips, search field, retained overflow actions, and system-font decision. Do not rewrite the comp history or mark a pending gate as passed to reconcile documentation.

One behavioral persistence defect remains in the reviewed baseline: exporting JSON through the system file picker returns the QR screen to its top. The parent supplied the failed functional assertion; the baseline QrActivity.render implementation confirms that busy changes remove and reconstruct the ScrollView, making successive posted restoration unreliable. This is F1 below.

## fidelity

Opened the approved variant2.png and all eleven required captures: v101-phone-light, v101-phone-dark, v101-phone-large, v101-editor-light, v101-editor-large, v101-qr-open, v101-qr-secure, v101-qr-preview, v101-qr-large, v101-keys, and v101-scanner. They contain the claimed native content and are usable evidence. The scanner pattern is the emulator's synthetic camera feed, not a broken render. The preview capture shows a scrolled field section; it supports field legibility, while its offscreen name/actions are supported only by source inspection and the supplied functional evidence.

Also opened the final side-by-side and every paired region image, and read report.json, spec.json, and state.json. The automated final score is **66.74%, drift**. The hero phase is open with no gate result; no hero diff exists. **Pixel fidelity has not passed.**

The generated region specification is unreliable as a semantic locator: its `search` crop contains the two action buttons, `filters` contains the first equipment name, and `sort` contains room 305. Several automated missing/contradicted labels therefore compare the wrong content. Neither those labels nor the web-oriented font match justify replacing correct native controls. Roboto is an intentional Android choice.

Visible topology does carry the selected direction: cobalt app bar, equipment heading, equally salient Scan/Add actions, search, status filters, location/sort, and open ruled inventory rows with strong room numbers. It has not fallen back to the discarded card/gradient dashboard. Native chips replace the comp's tabs, and retained counts plus row menus produce a taller register: roughly two full sample rows appear at 1.0 and one at 1.3. That is a documented density departure, not evidence that the rows or controls are missing. It does not warrant a wholesale rebuild at this scope.

The editor, QR output, decoded preview, and keys continue the same palette and type hierarchy. Open JSON and protected AES + RSA are plainly distinguishable. The open-mode copy describes information exposure; protected-mode copy explains the shared key and snapshot behavior. Data review precedes saving in the inspected source. No invented profile, account, or decorative metric cards were introduced.

## ceiling

Native conventions and task clarity are the appropriate ceiling here. The finish is coherent, legible operational software, with distinctive emphasis supplied by the open register and room column. It does not need display fonts, ornamental motion, or more surface decoration.

The shown 1.3 text wraps instead of colliding. Editor save remains clear above navigation chrome. Source inspection confirms Material controls, sp typography, and minimum target sizing on the reviewed actions. Representative token contrast is sufficient: secondary text is 5.87:1 on the light background and 8.54:1 on the dark surface; primary button foregrounds exceed 7:1. The camera instruction is the concrete exception, F2.

No HTML/CSS detector was run because it has no native verdict. The packet reports 32 passing tests, lint with 0 errors / 72 warnings, generated open/protected PNGs, protected PNG/JSON decoding, password-protected key transfer, and camera permission/launch checks. These checks were supplied, not independently rerun. This review makes no tablet, physical-camera scan reliability, hardware performance, predictive-Back, or comprehensive assistive-technology claim.

## material_fixes

| ID | Priority | Evidence and impact | Required bounded correction | Acceptance evidence |
| --- | --- | --- | --- | --- |
| F1 | P2 | The supplied SAF JSON-export assertion fails: the QR page returns to its top. Baseline QrActivity.render rebuilds its ScrollView on busy transitions. Users lose the export controls and their place during a routine repeated task. | Keep the existing content/scroll view for busy-only changes, update progress and enabled states separately, and preserve the page, selected QR mode, and scroll position through picker success or cancellation. Re-enable controls after completion. | From below the fold, export JSON and return with the same export area visible; confirm successful output and enabled controls. Check picker cancellation and the PNG action as well. Include the relevant after-return capture plus the same required screen matrix for the verdict pass. |
| F2 | P2 | In v101-scanner.png the white instruction sits directly on camera imagery. The background near the instruction at (900,1745) is #9F9F1A, giving only about 2.8:1 against white. Bright real scenes can likewise make the instruction unreadable. | Give only the scanner instruction a stable dark backing or sufficiently opaque scrim, with padding and clearance above the navigation bar. Retain the camera preview and scanning window. | Recapture v101-scanner.png against the same bright synthetic scene. The entire instruction must remain readable with at least 4.5:1 contrast against its guaranteed backing and no navigation-bar overlap. |

Apply these two fixes as one bounded batch, rebuild, and return to this reviewer for a verdict scoring F1 and F2. No other visual polishing is requested by this review. The correction batch must not be described as a passed comp gate.

## keep

- Keep equal Scan/Add prominence and the same inventory identity across register, QR, review, and editor.
- Keep the open rows, clear room column, written statuses, and retained record overflow actions.
- Keep first-class dark colors, native Roboto/Material behavior, 48dp touch targets, and genuine system font scaling.
- Keep the two explicit QR modes, exposure/key explanations, mandatory review before mutation, and the password-protected organization-key workflow.
- Keep the white quiet zone around QR images in both themes and the absence of decorative imagery, gradients, nested cards, or fake production data.

After F1 and F2 are resolved, the next step is the bounded verdict pass and final design documentation, not another independent polishing loop.
