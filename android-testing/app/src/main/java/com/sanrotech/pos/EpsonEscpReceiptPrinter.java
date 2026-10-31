package com.sanrotech.pos;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/**
 * Basic text-mode Epson ESC/P output, 80 columns at 10 CPI for a continuous-form
 * printer. No form feed, cutter, payment command, or drawer pulse is ever sent.
 * This is an initial USB compatibility path; stationery alignment requires UAT.
 */
final class EpsonEscpReceiptPrinter {
    private static final int COLS = 80;
    private static final int MAX_LINES = 600;
    private static final int MAX_BYTES = 30000;

    private EpsonEscpReceiptPrinter() {}

    static byte[] testPage() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        init(b);
        line(b,"SANRO POS TUNAS BARU - USB EPSON LX-300+ TEST");
        line(b,"ESC/P TEXT MODE 10 CPI / 80 COLUMNS");
        line(b,"----------------------------------------");
        line(b,"Jika teks ini tercetak, transfer data USB diterima printer.");
        line(b,"Cek arah kertas, spasi, dan posisi cetak sebelum uji nota.");
        line(b,"");
        line(b,"");
        return b.toByteArray();
    }

    static byte[] serialize(JSONObject receipt) throws JSONException {
        if (receipt.optInt("paperWidth") != 80) throw new JSONException("Data nota belum sesuai format pengujian.");
        String reference = safe(receipt.optString("reference", ""), 64);
        JSONArray content = receipt.optJSONArray("content");
        if (reference.isEmpty() || content == null || content.length() < 6 || content.length() > 180)
            throw new JSONException("Data nota tidak valid.");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        init(b);
        int lines=0;
        for(int i=0;i<content.length();i++) {
            JSONObject item=content.optJSONObject(i);
            if (item == null) throw new JSONException("Data baris tidak valid.");
            String type=item.optString("type","");
            switch(type) {
                case "text": {
                    String txt=safe(item.optString("text", ""),240);
                    if(txt.isEmpty()) throw new JSONException("Teks nota kosong.");
                    String align=item.optString("align","left");
                    if(!align.equals("left")&&!align.equals("center")&&!align.equals("right"))
                        throw new JSONException("Perataan tidak valid.");
                    boolean bold=item.optBoolean("bold",false);
                    if(bold) esc(b,0x1B,0x45);
                    lines+=wrapped(b,txt,COLS,align);
                    if(bold) esc(b,0x1B,0x46);
                    break;
                }
                case "row": {
                    String left=safe(item.optString("left",""),240);
                    String right=safe(item.optString("right",""),80);
                    if(left.isEmpty()||right.isEmpty())throw new JSONException("Rincian baris tidak lengkap.");
                    if(item.optBoolean("bold",false)) esc(b,0x1B,0x45);
                    if(left.length()+right.length()+1<=COLS) {
                        line(b,left+spaces(COLS-left.length()-right.length())+right); lines++;
                    } else {
                        lines+=wrapped(b,left,COLS,"left");
                        lines+=wrapped(b,right,COLS,"right");
                    }
                    if(item.optBoolean("bold",false)) esc(b,0x1B,0x46);
                    break;
                }
                case "divider": {
                    String ch=item.optString("char","-").equals("=")?"=":"-";
                    line(b,repeat(ch,COLS)); lines++;
                    break;
                }
                case "feed": {
                    int count=item.optInt("lines",1);
                    if(count<1||count>4)throw new JSONException("Feed tidak valid.");
                    for(int k=0;k<count;k++)line(b,"");
                    lines+=count;
                    break;
                }
                default: throw new JSONException("Jenis baris tidak didukung.");
            }
            if(lines>MAX_LINES || b.size()>MAX_BYTES) throw new JSONException("Nota melebihi batas printer.");
        }
        esc(b,0x1B,0x46); // emphasized off
        // Deliberately no FF (0x0C): continuous form should not eject a whole page.
        return b.toByteArray();
    }

    private static void init(ByteArrayOutputStream b) {
        esc(b,0x1B,0x40); // ESC @: initialize Epson ESC/P
        esc(b,0x1B,0x50); // ESC P: 10 CPI pica
        esc(b,0x1B,0x32); // ESC 2: normal line spacing
        esc(b,0x1B,0x46); // ESC F: emphasized off
    }
    private static void esc(ByteArrayOutputStream b,int... codes){for(int c:codes)b.write(c);}
    private static void line(ByteArrayOutputStream b,String s){byte[] d=s.getBytes(StandardCharsets.US_ASCII);b.write(d,0,d.length);b.write(0x0D);b.write(0x0A);}
    private static int wrapped(ByteArrayOutputStream b,String s,int col,String align){
        int total=0;
        while(s.length()>col){
            int p=s.lastIndexOf(' ',col);
            if(p<col/2)p=col;
            String head=s.substring(0,p).trim();
            line(b,align(head,col,align)); total++;
            s=s.substring(p).trim();
        }
        if(!s.isEmpty()){line(b,align(s,col,align));total++;}
        return total;
    }
    private static String align(String s,int col,String a){
        if(a.equals("center"))return spaces((col-s.length())/2)+s;
        if(a.equals("right"))return spaces(col-s.length())+s;
        return s;
    }
    private static String spaces(int n){return repeat(" ",Math.max(0,n));}
    private static String repeat(String s,int n){StringBuilder b=new StringBuilder();for(int i=0;i<n;i++)b.append(s);return b.toString();}
    private static String safe(String raw,int max) throws JSONException {
        if(raw==null||raw.length()>max)throw new JSONException("Panjang teks tidak valid.");
        String s=Normalizer.normalize(raw,Normalizer.Form.NFKD)
                .replace('–','-').replace('—','-').replace('×','x')
                .replace('‘','\'').replace('’','\'');
        StringBuilder b=new StringBuilder();
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i);
            if(c>=32&&c<=126)b.append(c);
            else if(Character.isWhitespace(c))b.append(' ');
        }
        return b.toString().replaceAll(" +"," ").trim();
    }
}
