/**
 * Infantry AI to-do tool for pi (TODO-PROGRESS-1). Written by the Infantry AI daemon and loaded with `-e` on every pi session.
 *
 * pi has no built-in to-do tool. This registers `todowrite` with the same shape as opencode's: every call sends the FULL
 * list, so the daemon reads the list straight from the call's arguments (it drives the to-do box and the progress bar).
 * No imports on purpose: the parameters are a plain JSON schema, so the file loads from any directory.
 */
type Todo = { content: string; status: "pending" | "in_progress" | "completed" | "cancelled"; activeForm?: string };

export default function (pi: any) {
	let todos: Todo[] = [];
	pi.registerTool({
		name: "todowrite",
		label: "To-do",
		description:
			"Write your to-do list for the current task. Send the FULL list every time (it replaces the previous one). " +
			"Keep it current: mark one item in_progress while you work on it and completed as soon as it is really done. " +
			"The list is shown to the owner and drives your progress bar.",
		parameters: {
			type: "object",
			properties: {
				todos: {
					type: "array",
					description: "The whole list, in order.",
					items: {
						type: "object",
						properties: {
							content: { type: "string", description: "What to do (imperative)." },
							status: { type: "string", enum: ["pending", "in_progress", "completed", "cancelled"] },
							activeForm: { type: "string", description: "Optional present-tense form shown while in progress." },
						},
						required: ["content", "status"],
					},
				},
			},
			required: ["todos"],
		},
		async execute(_toolCallId: string, params: { todos?: Todo[] }) {
			todos = Array.isArray(params?.todos) ? params.todos : [];
			const done = todos.filter((t) => t.status === "completed" || t.status === "cancelled").length;
			const text = todos.length
				? `${done}/${todos.length} done\n` +
					todos.map((t) => `[${t.status === "completed" ? "x" : t.status === "in_progress" ? ">" : " "}] ${t.content}`).join("\n")
				: "To-do list cleared";
			return { content: [{ type: "text", text }], details: { todos } };
		},
	});
}
