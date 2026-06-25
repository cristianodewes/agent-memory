package com.agentmemory;

import com.agentmemory.security.TokenGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// Auth here is a single shared bearer token (issue #38), not username/password accounts, so exclude
// Boot's default user/password auto-config — it would otherwise log a misleading "generated security
// password". Our own SecurityConfiguration owns the filter chain.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class AgentMemoryServerApplication {

	public static void main(String[] args) {
		// `--generate-auth-token` (issue #38): print a fresh bearer token and exit, without booting the
		// server (no DB/LLM probe). The operator sets it as agent-memory.auth.token to expose the server.
		if (TokenGenerator.isRequested(args)) {
			System.out.println(TokenGenerator.generate());
			return;
		}
		SpringApplication.run(AgentMemoryServerApplication.class, args);
	}

}
