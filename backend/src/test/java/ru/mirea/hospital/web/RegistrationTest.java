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
    void registrationStartsAPatientSessionWithoutStaffAccess() throws Exception {
        String email = "Signup@hospital.test";
        String password = "ExampleSecret42!";
        String account = json.writeValueAsString(Map.of("displayName", "Иванов Иван Иванович",
                "email", email, "password", password));

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(account.replace(password, "short"))).andExpect(status().isBadRequest());
        var registered = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(account))
                .andExpect(status().isCreated()).andExpect(cookie().httpOnly("hospital_refresh", true))
                .andExpect(jsonPath("$.user.roles[0]").value("PATIENT")).andReturn().getResponse();
        String initial = json.readTree(registered.getContentAsByteArray()).get("accessToken").asText();
        mvc.perform(get("/api/patient/doctors").header("Authorization", "Bearer " + initial)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(account.replace("Signup", "signup"))).andExpect(status().isConflict());

        String login = json.writeValueAsString(Map.of("email", email, "password", password, "clientType", "WEB"));
        var session = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.roles[0]").value("PATIENT"))
                .andExpect(jsonPath("$.user.passwordChangeRequired").value(false))
                .andReturn().getResponse();
        String token = "Bearer " + json.readTree(session.getContentAsByteArray()).get("accessToken").asText();
        mvc.perform(get("/api/auth/me").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("signup@hospital.test"));
        mvc.perform(get("/api/appointments").header("Authorization", token)).andExpect(status().isForbidden());
    }

    @Test
    void patientCanBookAndOnlySeeOwnAppointments(@Autowired org.springframework.jdbc.core.JdbcTemplate db,
            @Autowired ru.mirea.hospital.web.repository.DoctorRepository doctors) throws Exception {
        // In production Flyway provisions the linked legacy profile using a PostgreSQL trigger.
        db.execute("create table if not exists users (web_account_id uuid, login varchar(40), active boolean)");
        String[] tokens = new String[2];
        for (int i = 0; i < tokens.length; i++) {
            String body = json.writeValueAsString(Map.of("displayName", "Пациент " + i,
                    "email", "patient" + i + "@clinic.test", "password", "DemoBooking42!"));
            var reply = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated()).andReturn().getResponse();
            var session = json.readTree(reply.getContentAsByteArray());
            tokens[i] = "Bearer " + session.get("accessToken").asText();
            db.update("insert into users(web_account_id, login, active) values (?, ?, true)",
                    java.util.UUID.fromString(session.at("/user/id").asText()), "patient" + i);
        }

        var doctor = doctors.saveAndFlush(new ru.mirea.hospital.web.domain.Doctor(
                java.util.UUID.randomUUID(), "TEST-DOC", "Иванов Иван", "Терапевт"));
        var day = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Moscow")).plusDays(1);
        while (day.getDayOfWeek().getValue() > 5) day = day.plusDays(1);
        String booking = json.writeValueAsString(Map.of("doctorId", doctor.getId(),
                "startsAt", day.atTime(9, 0).toString(), "reason", "Консультация"));
        mvc.perform(post("/api/patient/appointments").header("Authorization", tokens[0])
                .contentType(MediaType.APPLICATION_JSON).content(booking)).andExpect(status().isCreated());
        mvc.perform(get("/api/patient/appointments").header("Authorization", tokens[0]))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].reason").value("Консультация"));
        mvc.perform(get("/api/patient/appointments").header("Authorization", tokens[1]))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(post("/api/patient/appointments").header("Authorization", tokens[1])
                .contentType(MediaType.APPLICATION_JSON).content(booking)).andExpect(status().isConflict());

        var list = mvc.perform(get("/api/patient/appointments").header("Authorization", tokens[0]))
                .andReturn().getResponse();
        var own = json.readTree(list.getContentAsByteArray()).get(0);
        String endpoint = "/api/patient/appointments/" + own.get("id").asText();
        long version = own.get("version").asLong();
        mvc.perform(post(endpoint + "/cancel").param("version", String.valueOf(version))
                .header("Authorization", tokens[1])).andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(endpoint)
                .param("version", String.valueOf(version)).header("Authorization", tokens[1])
                .contentType(MediaType.APPLICATION_JSON).content(booking)).andExpect(status().isNotFound());
        mvc.perform(post(endpoint + "/cancel").param("version", String.valueOf(version + 1))
                .header("Authorization", tokens[0])).andExpect(status().isConflict());
        mvc.perform(post(endpoint + "/cancel").param("version", String.valueOf(version))
                .header("Authorization", tokens[0])).andExpect(status().isNoContent());
        mvc.perform(post("/api/patient/appointments").header("Authorization", tokens[1])
                .contentType(MediaType.APPLICATION_JSON).content(booking)).andExpect(status().isCreated());
    }
}
