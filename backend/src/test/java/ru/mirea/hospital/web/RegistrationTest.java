package ru.mirea.hospital.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void newAccountCanSignInButCannotReadPatientRecords() throws Exception {
        String email = "Signup@hospital.test";
        String password = "ExampleSecret42!";
        String account = json.writeValueAsString(Map.of("displayName", "Иванов Иван Иванович",
                "email", email, "password", password));

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(account.replace(password, "short"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(account))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(account.replace("Signup", "signup"))).andExpect(status().isConflict());

        String login = json.writeValueAsString(Map.of("email", email, "password", password, "clientType", "WEB"));
        var session = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.roles").isEmpty())
                .andExpect(jsonPath("$.user.passwordChangeRequired").value(false))
                .andReturn().getResponse();
        String token = "Bearer " + json.readTree(session.getContentAsByteArray()).get("accessToken").asText();
        mvc.perform(get("/api/auth/me").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("signup@hospital.test"));
        mvc.perform(get("/api/appointments").header("Authorization", token)).andExpect(status().isForbidden());
    }
}
