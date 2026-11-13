-- hello-card-demo: demo script the Acme_HelloCard plugin ships: logs a greeting (and the event that ran it, when a hook did)
-- Scenario: You want to see a plugin-shipped script run, by hand or from its hook.
-- Args:
--   name (string, "world"): who to greet
-- Hook: acme.hello (declared in Acme_HelloCard's module.json as hook Acme_HelloCard.greet)
-- Gotchas: Read-only: it queues no action. Runs with the same sandbox and gates as any script.
local a = input.args or {}
ih.log("hello, " .. tostring(a.name or "world") .. "! (from the Acme_HelloCard plugin)")
if type(a.event) == "table" then ih.log("ran by hook for event " .. tostring(a.event.name)) end
