-- agent-tasks-smoke: exercises the per-agent ticket queue tools on two throwaway tickets (not hooked).
-- Run: infantry_script_run name=agent-tasks-smoke dryRun=true   (args: agent = agent id for the queue, optional)
-- Phase 1 creates the tickets; phase 2 queues/lists/removes/unassigns/switches; phase 3 prints each answer.
local a = input.args or {}
local function result(i) return input.results and input.results[i] end
if input.phase == 1 then
  ih.infantry_ticket_create{title = "smoke A (agent-tasks-smoke)", status = "backlog"}
  ih.infantry_ticket_create{title = "smoke B (agent-tasks-smoke)", status = "backlog"}
  ih.again()
elseif input.phase == 2 then
  local ta = result(1) and result(1).json and result(1).json.id or "ticket-A"
  local tb = result(2) and result(2).json and result(2).json.id or "ticket-B"
  local agent = a.agent or "agent-smoke"
  ih.infantry_agent_queue{action = "add", agentId = agent, ticketId = ta}
  ih.infantry_agent_queue{action = "add", agentId = agent, ticketId = tb}
  ih.infantry_agent_queue{action = "list", agentId = agent}
  ih.infantry_agent_queue{action = "remove", agentId = agent, ticketId = tb}
  ih.infantry_ticket_unassign{ticketId = ta}
  ih.infantry_agent_switch{agentId = agent, ticketId = tb}
  ih.again()
else
  for i, r in ipairs(input.results or {}) do
    ih.log(i .. " " .. tostring(r.tool) .. " ok=" .. tostring(r.ok) .. " " .. tostring(r.text or ""):sub(1, 200))
  end
end
