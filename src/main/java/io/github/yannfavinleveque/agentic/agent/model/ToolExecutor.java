package io.github.yannfavinleveque.agentic.agent.model;

/**
 * Functional interface for executing tool calls in autonomous agent mode.
 * <p>
 * Implement this interface to handle tool calls made by an autonomous agent. The library
 * automatically manages the conversation loop (request agent, receive tool calls, execute them
 * via this executor, send results back, repeat until task completion).
 * </p>
 *
 * <pre>{@code
 * ToolExecutor executor = call -> {
 *     switch (call.getName()) {
 *         case "search_products":
 *             SearchParams params = call.getArgumentsAs(SearchParams.class);
 *             return productService.search(params.getQuery());
 *         case "get_details":
 *             return productService.getDetails(call.getArgumentsAsMap().get("id").toString());
 *         default:
 *             return "Unknown function: " + call.getName();
 *     }
 * };
 *
 * AgentResult result = agentService.requestAgent("my-agent", "Find product X", executor).join();
 * }</pre>
 *
 * @see FunctionCall
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * Executes a function call and returns the result as a string.
     * <p>
     * The returned string is sent back to the LLM as the tool result. If an exception is thrown,
     * its message is sent as an error result and the loop continues (the LLM can decide how to
     * handle the error).
     * </p>
     *
     * @param functionCall The function call to execute (contains name, id, arguments)
     * @return String result of the function execution
     * @throws Exception if execution fails
     */
    String execute(FunctionCall functionCall) throws Exception;
}
