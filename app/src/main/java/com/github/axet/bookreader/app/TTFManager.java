package com.github.axet.bookreader.app;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import android.util.Log;

import com.github.axet.androidlibrary.widgets.CacheImagesAdapter;

import org.geometerplus.zlibrary.core.util.ZLTTFInfoDetector;
import org.geometerplus.zlibrary.ui.android.view.AndroidFontUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;

public class TTFManager { // .ttf *.otf *.ttc
    public static String TAG = TTFManager.class.getSimpleName();

    public static File USER_FONTS; // /sdcard/Fonts
    public static final File[] SYSTEM_FONTS = {new File("/system/fonts"), new File("/system/font"), new File("/data/fonts")};

    public Context context;
    public File appFonts; // app home folder, /sdcard/Android/data/.../files/Fonts
    public ArrayList<Uri> uris = new ArrayList<>(); // files and context://
    public ArrayList<Font> old = new ArrayList<>();
    public HashMap<File, Typeface> ourFontFileMap = new HashMap<>();

    static {
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null)
            USER_FONTS = new File(ext, "Fonts");
    }

    public static class Font implements Comparable<Font> {
        public String name;
        public Uri uri;
        public int index; // ttc index

        public Font(String n, Uri f) {
            name = n;
            uri = f;
            index = -1;
        }

        public Font(String n, Uri f, int i) {
            this(n, f);
            index = i;
        }

        @Override
        public int compareTo(Font o) {
            int i = uri.compareTo(o.uri);
            if (i != 0)
                return i;
            return Integer.compare(index, o.index);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Font font = (Font) o;
            return index == font.index && uri.equals(font.uri);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(new Object[]{uri, index});
        }
    }

    public static class TTCFile extends File {
        public Uri uri;
        public int index;

        public TTCFile(@NonNull String pathname) {
            super(pathname);
        }

        public TTCFile(Uri f, int i) {
            super(f.getPath());
            uri = f;
            index = i;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TTCFile ttcFile = (TTCFile) o;
            return index == ttcFile.index && uri.equals(ttcFile.uri);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(new Object[]{super.hashCode(), uri, index});
        }
    }

    // http://www.ulduzsoft.com/2012/01/enumerating-the-fonts-on-android-platform/
    public static class TTFAnalyzer {
        private CacheImagesAdapter.SeekInputStream m_file = null; // Font file; must be seekable

        // This function parses the TTF file and returns the font name specified in the file
        public String getTtfFontName() {
            try {
                // The TTF file consist of several sections called "tables", and we need to know how many of them are there.
                int numTables = readWord();

                // Skip the rest in the header
                readWord(); // skip searchRange
                readWord(); // skip entrySelector
                readWord(); // skip rangeShift

                // Now we can read the tables
                for (int i = 0; i < numTables; i++) {
                    // Read the table entry
                    int tag = readDword();
                    readDword(); // skip checksum
                    int offset = readDword();
                    int length = readDword();

                    // Now here' the trick. 'name' field actually contains the textual string name.
                    // So the 'name' string in characters equals to 0x6E616D65
                    if (tag == 0x6E616D65) {
                        // Here's the name section. Read it completely into the allocated buffer
                        byte[] table = new byte[length];

                        m_file.seek(offset);
                        read(table);

                        // This is also a table. See http://developer.apple.com/fonts/ttrefman/rm06/Chap6name.html
                        // According to Table 36, the total number of table records is stored in the second word, at the offset 2.
                        // Getting the count and string offset - remembering it's big endian.
                        int count = getWord(table, 2);
                        int string_offset = getWord(table, 4);

                        // Record starts from offset 6
                        for (int record = 0; record < count; record++) {
                            // Table 37 tells us that each record is 6 words -> 12 bytes, and that the nameID is 4th word so its offset is 6.
                            // We also need to account for the first 6 bytes of the header above (Table 36), so...
                            int nameid_offset = record * 12 + 6;
                            int platformID = getWord(table, nameid_offset);
                            int nameid_value = getWord(table, nameid_offset + 6);

                            // Table 42 lists the valid name Identifiers. We're interested in 4 but not in Unicode encoding (for simplicity).
                            // The encoding is stored as PlatformID and we're interested in Mac encoding
                            if (nameid_value == 4 && platformID == 1) {
                                // We need the string offset and length, which are the word 6 and 5 respectively
                                int name_length = getWord(table, nameid_offset + 8);
                                int name_offset = getWord(table, nameid_offset + 10);

                                // The real name string offset is calculated by adding the string_offset
                                name_offset = name_offset + string_offset;

                                // Make sure it is inside the array
                                if (name_offset >= 0 && name_offset + name_length < table.length)
                                    return new String(table, name_offset, name_length);
                            }
                        }
                    }
                }

                return null;
            } catch (FileNotFoundException e) { // Permissions?
                return null;
            } catch (IOException e) { // Most likely a corrupted font file
                return null;
            }
        }

        public String getTtfFontName(File file) {
            int tag = 0;
            try {
                m_file = new CacheImagesAdapter.SeekInputStream(new FileInputStream(file));
                tag = readDword();
            } catch (IOException e) {
                return null;
            }
            switch (tag) {
                case 0x74727565:
                case 0x00010000:
                case 0x4f54544f:
                    return getTtfFontName();
            }
            return null;
        }

        public String[] getTTCFontNames() {
            try {
                int major = readWord();
                int min = readWord();
                int num = readDword();
                int[] nn = new int[num];
                for (int i = 0; i < num; i++)
                    nn[i] = readDword();
                String[] ss = new String[num];
                for (int i = 0; i < num; i++) {
                    m_file.seek(nn[i]);
                    int tag = readDword();
                    switch (tag) {
                        case 0x74727565:
                        case 0x00010000:
                        case 0x4f54544f:
                            ss[i] = getTtfFontName();
                            break;
                    }
                }
                return ss;
            } catch (Exception e) {
                return null;
            }
        }

        public String[] getNames(File file) {
            try {
                return getNames(new FileInputStream(file));
            } catch (Exception e) {
                return null;
            }
        }

        public String[] getNames(InputStream is) {
            try {
                m_file = new CacheImagesAdapter.SeekInputStream(is);
                int tag = readDword();
                switch (tag) {
                    case 0x74746366: //'ttcf':
                        return getTTCFontNames();
                    case 0x74727565:
                    case 0x00010000:
                    case 0x4f54544f:
                        return new String[]{getTtfFontName()};
                }
            } catch (Exception e) {
                return null;
            }
            return null;
        }

        private int readByte() throws IOException { // Helper I/O functions
            return m_file.read() & 0xFF;
        }

        private int readWord() throws IOException {
            int b1 = readByte();
            int b2 = readByte();
            return b1 << 8 | b2;
        }

        private int readDword() throws IOException {
            int b1 = readByte();
            int b2 = readByte();
            int b3 = readByte();
            int b4 = readByte();
            return b1 << 24 | b2 << 16 | b3 << 8 | b4;
        }

        private void read(byte[] array) throws IOException {
            if (m_file.read(array) != array.length)
                throw new IOException();
        }

        // Helper
        private int getWord(byte[] array, int offset) {
            int b1 = array[offset] & 0xFF;
            int b2 = array[offset + 1] & 0xFF;
            return b1 << 8 | b2;
        }
    }

    public ArrayList<Font> enumerateFonts() {
        ArrayList<Font> ff = new ArrayList<>();
        TTFAnalyzer a = new TTFAnalyzer();
        for (Uri uri : uris) {
            String s = uri.getScheme();
            if (s.equals(ContentResolver.SCHEME_FILE)) {
                File dir = Storage.getFile(uri);
                if (!dir.exists())
                    continue;
                File[] files = dir.listFiles();
                if (files == null)
                    continue;
                for (File file : files) {
                    String[] nn = a.getNames(file);
                    if (nn != null) {
                        if (nn.length == 1) {
                            String name = nn[0];
                            if (name != null && !name.isEmpty())
                                ff.add(new Font(name, Uri.fromFile(file)));
                        } else {
                            for (int i = 0; i < nn.length; i++) {
                                String name = nn[i];
                                if (name != null && !name.isEmpty())
                                    ff.add(new Font(name, Uri.fromFile(file), i));
                            }
                        }
                    }
                }
            } else if (s.equals(ContentResolver.SCHEME_CONTENT)) {
                ContentResolver resolver = context.getContentResolver();
                ArrayList<Storage.Node> nn = Storage.list(context, uri);
                for (Storage.Node n : nn) {
                    try {
                        InputStream is = resolver.openInputStream(n.uri);
                        String[] names = a.getNames(is);
                        if (names != null) {
                            if (names.length == 1) {
                                String name = names[0];
                                if (name != null && !name.isEmpty())
                                    ff.add(new Font(name, n.uri));
                            } else {
                                for (int i = 0; i < names.length; i++) {
                                    String name = names[i];
                                    if (name != null && !name.isEmpty())
                                        ff.add(new Font(name, n.uri, i));
                                }
                            }
                        }
                    } catch (IOException e) {
                        Log.w(TAG, e);
                    }
                }
            }
        }
        Collections.sort(ff);
        return ff.isEmpty() ? null : ff;
    }

    public TTFManager(Context context) {
        this.context = context;
        init();
    }

    public void init() {
        ArrayList<File> fonts = new ArrayList<>(Arrays.asList(SYSTEM_FONTS));
        if (USER_FONTS != null)
            fonts.add(USER_FONTS);
        File fl = context.getFilesDir(); // /data/.../files/Fonts
        if (fl != null) {
            fl = new File(fl, "Fonts");
            fonts.add(fl);
        }
        if (Build.VERSION.SDK_INT >= 19) {
            File[] fl2 = context.getExternalFilesDirs("Fonts");
            if (fl2 != null) {
                for (File f : fl2) {
                    if (f != null)
                        fonts.add(f);
                }
                appFonts = fl2[0];
            }
        }
        if (appFonts == null)
            appFonts = fl;
        uris.clear();
        for (File f : fonts)
            uris.add(Uri.fromFile(f));
    }

    public void setFolder(Uri uri) {
        init();
        uris.add(uri);
    }

    public void preloadFonts() {
        List<File> files = new ArrayList<>();
        HashMap<TTFManager.Font, File> ttc = new HashMap<>();
        ArrayList<Font> ff = enumerateFonts();
        if (old.equals(ff)) {
            Log.d(TAG, "preloadFonts - no new items");
            return;
        }
        old = ff;
        for (TTFManager.Font f : ff) {
            if (f.index == -1 && f.uri.getScheme().equals(ContentResolver.SCHEME_FILE))
                files.add(Storage.getFile(f.uri));
            else
                ttc.put(f, Storage.getFile(f.uri));
        }
        AndroidFontUtil.ourFileSet = new TreeSet<>();
        AndroidFontUtil.ourFontFileMap = new ZLTTFInfoDetector().collectFonts(files);
        ourFontFileMap = new HashMap<>();
        if (Build.VERSION.SDK_INT >= 26) { // ttc index support API26
            for (TTFManager.Font f : ttc.keySet()) {
                try {
                    TTCFile tf = new TTCFile(f.uri, f.index);
                    Typeface ttf = load(tf);
                    AndroidFontUtil.ourTypefaces.put(f.name, new Typeface[]{ttf, null, null, null});
                    AndroidFontUtil.ourFontFileMap.put(f.name, new TTCFile[]{tf, null, null, null});
                    ourFontFileMap.put(tf, ttf);
                } catch (Exception e) {
                    Log.w(TAG, e);
                }
            }
        }
    }

    public Typeface load(File file) {
        Typeface tf = ourFontFileMap.get(file);
        if (tf != null)
            return tf;
        if (Build.VERSION.SDK_INT >= 26 && file instanceof TTCFile) {
            TTCFile tc = (TTCFile) file;
            String s = tc.uri.getScheme();
            if (s.equals(ContentResolver.SCHEME_FILE)) {
                return new Typeface.Builder(Storage.getFile(tc.uri)).setTtcIndex(tc.index).build();
            } else if (s.equals(ContentResolver.SCHEME_CONTENT)) {
                ContentResolver resolver = context.getContentResolver();
                try {
                    ParcelFileDescriptor fd = resolver.openFileDescriptor(tc.uri, "r");
                    if (tc.index == -1)
                        tf = new Typeface.Builder(fd.getFileDescriptor()).build();
                    else
                        tf = new Typeface.Builder(fd.getFileDescriptor()).setTtcIndex(tc.index).build();
                    ourFontFileMap.put(tc, tf);
                    return tf;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                throw new Storage.UnknownUri();
            }
        } else {
            tf = Typeface.createFromFile(file);
            ourFontFileMap.put(file, tf);
            return tf;
        }
    }
}
