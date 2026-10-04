package ru.mirea.hospital.fx;

import java.util.Arrays;
import ru.mirea.hospital.Main;

/** Entry point outside Application for the executable JAR. */
public final class HospitalLauncher {
    private HospitalLauncher() {}

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--cli"))
            Main.main(Arrays.copyOfRange(args, 1, args.length));
        else if (args.length > 0 && (args[0].equals("--seed") || args[0].equals("--init-admin")))
            Main.main(args);
        else HospitalApplication.main(args);
    }
}
