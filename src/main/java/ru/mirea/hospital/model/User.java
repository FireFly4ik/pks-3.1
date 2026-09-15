package ru.mirea.hospital.model;

public class User {
    private final long id;
    private final String login;
    private final String fullName;
    private final Role role;
    private final boolean active;

    public User(long id, String login, String fullName, Role role, boolean active) {
        this.id = id;
        this.login = login;
        this.fullName = fullName;
        this.role = role;
        this.active = active;
    }
    public long getId() { return id; }
    public String getLogin() { return login; }
    public String getFullName() { return fullName; }
    public Role getRole() { return role; }
    public boolean isActive() { return active; }
}
