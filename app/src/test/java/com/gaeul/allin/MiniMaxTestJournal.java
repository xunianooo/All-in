package com.gaeul.allin;
import java.io.*;
import java.nio.file.*;

/** Test-only directory sync adapter. Production journal remains fail-closed. */
final class MiniMaxTestJournal {
 static boolean unsupported(File dir) throws IOException {
  if (!System.getProperty("os.name", "").startsWith("Windows")) return false;
  try { new MiniMaxRefreshJournal(dir).syncDirectory(); return false; }
  catch (AccessDeniedException expected) { return true; }
 }
 static MiniMaxRefreshJournal fixture(File dir) throws IOException {
  if (!unsupported(dir)) return new MiniMaxRefreshJournal(dir);
  return new MiniMaxRefreshJournal(dir) { @Override void syncDirectory() {} };
 }
}
