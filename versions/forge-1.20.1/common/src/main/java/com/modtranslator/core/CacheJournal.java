package com.modtranslator.core;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
/** Durable cache deltas between atomic full checkpoints. */
final class CacheJournal {
    private CacheJournal() {}
    static List<String> recover(Path file) throws IOException {
        if(!Files.isRegularFile(file))return List.of();
        byte[] bytes=Files.readAllBytes(file);int end=bytes.length;
        while(end>0&&bytes[end-1]!='\n')end--;
        if(end!=bytes.length)try(FileChannel channel=FileChannel.open(file,StandardOpenOption.WRITE)){channel.truncate(end);channel.force(false);}
        return new String(bytes,0,end,StandardCharsets.UTF_8).lines().filter(line->!line.isBlank()).toList();
    }
    static void append(Path file,List<String> records) throws IOException {
        if(records.isEmpty())return;
        if(Files.isRegularFile(file)&&Files.size(file)>0)try(FileChannel channel=FileChannel.open(file,StandardOpenOption.READ)) {
            ByteBuffer last=ByteBuffer.allocate(1);channel.read(last,channel.size()-1);if(last.array()[0]!='\n')recover(file);
        }
        byte[] bytes=(String.join("\n",records)+"\n").getBytes(StandardCharsets.UTF_8);
        try(FileChannel channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)) {
            long before=channel.size();
            try {ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(false);}
            catch(IOException failure){try{channel.truncate(before);channel.force(false);}catch(IOException rollback){failure.addSuppressed(rollback);}throw failure;}
        }
    }
    static void clear(Path file) throws IOException {
        if(Files.isRegularFile(file))try(FileChannel channel=FileChannel.open(file,StandardOpenOption.WRITE)){channel.truncate(0);channel.force(false);}
    }
}
