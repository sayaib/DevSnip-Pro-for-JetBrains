/* Node's https module for the browser bundle: only the Agent options the axios shim reads (rejectUnauthorized). */
export class Agent {
  constructor(options) {
    this.options = options || {};
  }
}
export default { Agent };
