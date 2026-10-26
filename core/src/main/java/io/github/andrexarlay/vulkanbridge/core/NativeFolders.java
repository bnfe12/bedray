package io.github.andrexarlay.vulkanbridge.core;

import io.github.andrexarlay.vulkanbridge.platform.LogPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates {@code bedray/natives/}, {@code bedray/natives/streamline/} and a bilingual {@code README.txt} on the first start, so a
 * player finds an obvious place to put vendor DLLs (the way Iris has a {@code shaderpacks} folder). Nothing is downloaded.
 * Pure Java.
 */
public final class NativeFolders {
    public static final String README = "README.txt";
    static final String MARKER = "Bedray natives folder - README v";
    /** Bump when {@link #readmeText()} changes; an older README written by us is replaced, a README of any other origin is left alone. */
    static final int README_VERSION = 1;

    private NativeFolders() {}

    /** What {@link #ensureLayout} did. */
    public record Result(List<Path> createdDirs, boolean readmeWritten) {}

    /**
     * Creates both folders and the README when missing. IOException is thrown to the caller (who logs it). Refuses to use a link in
     * place of one of our folders.
     */
    public static Result ensureLayout(Path nativesDir) throws IOException {
        List<Path> created = new ArrayList<>();
        for (NativeCatalog.Folder f : NativeCatalog.Folder.values()) {
            Path dir = f.resolve(nativesDir);
            if (Files.isSymbolicLink(dir)) {
                throw new IOException(LogPaths.show(dir) + " is a symbolic link; Bedray will not use it as a natives folder");
            }
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir);
                created.add(dir);
            }
        }
        return new Result(created, writeReadmeIfNeeded(nativesDir.resolve(README)));
    }

    private static boolean writeReadmeIfNeeded(Path readme) throws IOException {
        if (Files.isSymbolicLink(readme)) {
            throw new IOException(LogPaths.show(readme) + " is a symbolic link; not overwriting it");
        }
        if (Files.exists(readme)) {
            int existing = versionOf(readme);
            if (existing < 0 || existing >= README_VERSION) {
                return false; // not ours (a user's file) or already current
            }
        }
        Files.writeString(readme, readmeText(), StandardCharsets.UTF_8);
        return true;
    }

    /** README version written by us, or -1 when the file does not start with our marker. */
    static int versionOf(Path readme) throws IOException {
        String first;
        try (var lines = Files.lines(readme, StandardCharsets.UTF_8)) {
            first = lines.findFirst().orElse("");
        } catch (java.io.UncheckedIOException e) {
            return -1; // not valid UTF-8: certainly not our file
        }
        if (first.startsWith("﻿")) {
            first = first.substring(1);
        }
        if (!first.startsWith(MARKER)) {
            return -1;
        }
        try {
            return Integer.parseInt(first.substring(MARKER.length()).strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String readmeText() {
        return String.join("\n",
            MARKER + README_VERSION,
            "",
            "=====================================================================",
            " [EN]  Bedray - vendor libraries (DLL) go here",
            "=====================================================================",
            "",
            "AMD FSR 3 (amd_fidelityfx_vk.dll) and Intel XeSS (libxess.dll) are INCLUDED in the Bedray jar. Bedray copies them here on the",
            "first start (byte for byte, SHA-256 checked). A file of the same name that you put here yourself is never overwritten.",
            "The NVIDIA files below are OPTIONAL and are NOT included: the licence does not allow it. Take them only from the official",
            "pages, accept NVIDIA's licence yourself, then:",
            "",
            "  EASIEST: open the game, Options > Video Settings > Bedray > \"Upscaler & Frame Gen DLLs...\"",
            "           and DRAG the vendor .zip or the .dll files into the game window. Bedray sorts them into the right folder.",
            "  BY HAND: copy the files as in the table. Restart the game afterwards (libraries are read at start).",
            "",
            "  File                       Folder              For                                   Official source",
            "  -------------------------  ------------------  ------------------------------------  --------------------------------------------",
            "  amd_fidelityfx_vk.dll      natives/            AMD FSR 3 upscaling + frame gen       INCLUDED (FidelityFX SDK v1.1.4, MIT)",
            "                                                                                         " + NativeCatalog.URL_FSR,
            "  libxess.dll                natives/            Intel XeSS upscaling                  INCLUDED (Intel Simplified Software License)",
            "                                                                                         " + NativeCatalog.URL_XESS,
            "  nvngx_dlss.dll             natives/            NVIDIA DLSS Super Resolution          NVIDIA DLSS SDK: " + NativeCatalog.URL_DLSS,
            "  nvngx_dlssd.dll            natives/            NVIDIA DLSS Ray Reconstruction        NVIDIA DLSS SDK: " + NativeCatalog.URL_DLSS,
            "  NRD.dll                    natives/            NVIDIA NRD denoiser                   " + NativeCatalog.URL_NRD,
            "  sl.interposer.dll          natives/streamline/ Streamline (needed for frame gen)     NVIDIA Streamline 2.x:",
            "  sl.common.dll              natives/streamline/                                       " + NativeCatalog.URL_STREAMLINE,
            "  sl.dlss_g.dll              natives/streamline/ DLSS Frame Generation",
            "  sl.reflex.dll              natives/streamline/ Reflex (low latency)",
            "  sl.pcl.dll                 natives/streamline/ Reflex PC latency markers",
            "  nvngx_dlssg.dll            natives/streamline/ Frame Generation model",
            "  sl.dlss.dll                natives/streamline/ DLSS Super Resolution through Streamline",
            "  sl.dlss_d.dll              natives/streamline/ Ray Reconstruction through Streamline",
            "  nvngx_dlss.dll             natives/streamline/ (a second copy, next to sl.dlss.dll)",
            "  nvngx_dlssd.dll            natives/streamline/ (a second copy, next to sl.dlss_d.dll)",
            "",
            "Frame Generation + Ray Reconstruction need these FOUR files in streamline/: sl.dlss.dll, sl.dlss_d.dll, nvngx_dlss.dll, nvngx_dlssd.dll.",
            "nvngx_dlss.dll and nvngx_dlssd.dll are needed in BOTH folders; the drag and drop installer puts a copy in each.",
            "Only x64 DLLs work. A wrong or damaged file is shown as invalid in the manager; the game never loads a file it rejected.",
            "",
            "Wrapper libraries (vkb_dlss.dll, vkb_nrd.dll, vkb_sl.dll) are not distributed. If you built them yourself:",
            "vkb_dlss.dll and vkb_nrd.dll go to natives/, vkb_sl.dll goes to natives/streamline/. Nothing else is needed from them.",
            "",
            "=====================================================================",
            " [RU]  Bedray - сюда кладутся библиотеки (DLL) производителей",
            "=====================================================================",
            "",
            "AMD FSR 3 (amd_fidelityfx_vk.dll) и Intel XeSS (libxess.dll) ВСТРОЕНЫ в jar Bedray. При первом запуске Bedray копирует их сюда",
            "побайтно (проверка SHA-256). Ваш собственный файл с тем же именем никогда не перезаписывается.",
            "Файлы NVIDIA ниже НЕОБЯЗАТЕЛЬНЫ и НЕ встроены: лицензия этого не разрешает. Берите их только с официальных страниц,",
            "лицензию NVIDIA принимаете сами, затем:",
            "",
            "  ПРОЩЕ ВСЕГО: в игре Настройки > Настройки видео > Bedray > \"Upscaler & Frame Gen DLLs...\"",
            "               и ПЕРЕТАЩИТЕ .zip или .dll в окно игры. Bedray сам разложит файлы по нужным папкам.",
            "  ВРУЧНУЮ:     скопируйте файлы по таблице выше. После этого перезапустите игру (библиотеки читаются при старте).",
            "",
            "Для Frame Generation + Ray Reconstruction нужны ЧЕТЫРЕ файла в streamline/: sl.dlss.dll, sl.dlss_d.dll, nvngx_dlss.dll, nvngx_dlssd.dll.",
            "nvngx_dlss.dll и nvngx_dlssd.dll нужны в ОБЕИХ папках; установщик перетаскиванием кладёт копию в каждую.",
            "Работают только x64 DLL. Неверный или повреждённый файл помечается в менеджере как недопустимый; игра не загружает отвергнутый файл.",
            "",
            "Библиотеки-обёртки (vkb_dlss.dll, vkb_nrd.dll, vkb_sl.dll) не распространяются. Если вы собрали их сами:",
            "vkb_dlss.dll и vkb_nrd.dll кладите в natives/, vkb_sl.dll - в natives/streamline/.",
            "",
            "Папки: <папка игры>/bedray/natives/ и <папка игры>/bedray/natives/streamline/",
            "");
    }
}
