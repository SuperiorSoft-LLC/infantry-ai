# Infantry AI Contributor License Agreement

> **DRAFT. Not legal advice. Not in effect.** The owner makes this official at the public launch, after a lawyer has
> read it. Until then `CONTRIBUTING.md` still refuses outside code.

Why a CLA and not only a DCO: files move between the MPL-2.0 core and the Apache-2.0 rest when a feature leaves the
daemon and becomes a plugin (see [Licensing](../wiki/licensing.md)). Without an explicit right to relicense, a
contributed file would be stuck under the license it came in with. This text is modeled on the Apache ICLA and CCLA.

"SuperiorSoft LLC" ("SuperiorSoft") is the steward of the Infantry AI project. "Contribution" means any work of
authorship, including code, documentation and tests, that you intentionally submit to the project.

## Part A: Individual Contributor License Agreement

By submitting a Contribution you ("You") agree:

1. **Copyright license.** You grant SuperiorSoft and every recipient of the project a perpetual, worldwide,
   non-exclusive, no-charge, royalty-free, irrevocable copyright license to reproduce, prepare derivative works of,
   publicly display, publicly perform, sublicense and distribute your Contributions and derivative works.
2. **Right to relicense.** You agree SuperiorSoft may distribute your Contributions, in whole or in part, under
   MPL-2.0, Apache-2.0, or any other license that the project uses or adopts for the file or module the Contribution
   lives in, and may move a Contribution from one of those licenses to another. A license SuperiorSoft chooses for a
   file must be one approved by the Open Source Initiative, unless the Contribution is part of a separate, clearly
   marked proprietary service that does not include it. You keep your copyright; this is a license, not an assignment.
3. **Patent license.** You grant SuperiorSoft and every recipient a perpetual, worldwide, non-exclusive, no-charge,
   royalty-free, irrevocable (except as below) patent license to make, have made, use, offer to sell, sell, import and
   otherwise transfer the Contribution, where the patents are ones you can license and are infringed by your
   Contribution alone or combined with the project. If someone sues alleging the project or a Contribution infringes
   a patent, the patent licenses you granted for that work end on the date the suit is filed.
4. **Your right to contribute.** You are legally entitled to grant the licenses above. If your employer has rights to
   what you create, you have permission from them, or they have signed Part B.
5. **Original work.** Each Contribution is your original creation, or you say so in the submission and name the source
   and its license. You will not submit code under a license that conflicts with MPL-2.0 or Apache-2.0.
6. **No warranty or support.** You provide Contributions "as is", and are not required to provide support.
7. **Updates.** You will tell SuperiorSoft if you become aware that any statement here was or has become inaccurate.

## Part B: Corporate Contributor License Agreement

This part applies when an entity ("Corporation") authorizes its employees or contractors to contribute.

1. The Corporation agrees to every term of Part A, with "You" meaning the Corporation, for all Contributions made by
   its designated employees or contractors within the scope of their work.
2. The Corporation represents that it has the authority to enter this agreement and that each designated person is
   authorized to submit Contributions on its behalf.
3. The Corporation keeps a list of designated persons and tells SuperiorSoft when it changes. The Corporation may
   revoke a person's authorization for future Contributions; past Contributions stay licensed.

Signing: the CLA check bot asks a contributor to sign Part A with their GitHub account. A Corporation sends its signed
Part B (entity name, signer, title, designated GitHub accounts) to the contact in `SECURITY.md` until a CLA mailbox
exists.

## Open points for the owner and the lawyer

- Whether the relicensing right (point 2 of Part A) is limited to OSI licenses, as drafted, or unrestricted.
- Whether a hosted, proprietary orchestrator may ever include Contributions (drafted: no, unless relicensed under an
  OSI license).
- Where signed Part B forms are stored.
