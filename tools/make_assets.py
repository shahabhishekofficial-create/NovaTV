#!/usr/bin/env python3
"""Generate NovaTV's launcher artwork using only Python's standard library."""
from pathlib import Path
import math
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
FONT = {
    "A": ["01110","10001","10001","11111","10001","10001","10001"],
    "B": ["11110","10001","10001","11110","10001","10001","11110"],
    "C": ["01111","10000","10000","10000","10000","10000","01111"],
    "D": ["11110","10001","10001","10001","10001","10001","11110"],
    "E": ["11111","10000","10000","11110","10000","10000","11111"],
    "F": ["11111","10000","10000","11110","10000","10000","10000"],
    "G": ["01111","10000","10000","10111","10001","10001","01111"],
    "H": ["10001","10001","10001","11111","10001","10001","10001"],
    "I": ["11111","00100","00100","00100","00100","00100","11111"],
    "J": ["00111","00010","00010","00010","10010","10010","01100"],
    "K": ["10001","10010","10100","11000","10100","10010","10001"],
    "L": ["10000","10000","10000","10000","10000","10000","11111"],
    "M": ["10001","11011","10101","10101","10001","10001","10001"],
    "N": ["10001","11001","10101","10011","10001","10001","10001"],
    "O": ["01110","10001","10001","10001","10001","10001","01110"],
    "P": ["11110","10001","10001","11110","10000","10000","10000"],
    "Q": ["01110","10001","10001","10001","10101","10010","01101"],
    "R": ["11110","10001","10001","11110","10100","10010","10001"],
    "S": ["01111","10000","10000","01110","00001","00001","11110"],
    "T": ["11111","00100","00100","00100","00100","00100","00100"],
    "U": ["10001","10001","10001","10001","10001","10001","01110"],
    "V": ["10001","10001","10001","10001","10001","01010","00100"],
    "W": ["10001","10001","10001","10101","10101","10101","01010"],
    "X": ["10001","10001","01010","00100","01010","10001","10001"],
    "Y": ["10001","10001","01010","00100","00100","00100","00100"],
    "Z": ["11111","00001","00010","00100","01000","10000","11111"],
    "0": ["01110","10001","10011","10101","11001","10001","01110"],
    "1": ["00100","01100","00100","00100","00100","00100","01110"],
    "2": ["01110","10001","00001","00010","00100","01000","11111"],
    ".": ["00000","00000","00000","00000","00000","00110","00110"],
    "-": ["00000","00000","00000","11111","00000","00000","00000"],
    " ": ["00000"] * 7,
}

class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.p = bytearray(w * h * 3)
    def pixel(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            i = (y * self.w + x) * 3
            self.p[i:i+3] = bytes(c)
    def rect(self, x, y, w, h, c):
        for yy in range(max(0,y), min(self.h,y+h)):
            for xx in range(max(0,x), min(self.w,x+w)):
                self.pixel(xx, yy, c)
    def circle(self, cx, cy, r, c):
        for y in range(cy-r, cy+r+1):
            for x in range(cx-r, cx+r+1):
                if (x-cx)**2 + (y-cy)**2 <= r*r:
                    self.pixel(x,y,c)
    def polygon(self, points, c):
        for y in range(min(p[1] for p in points), max(p[1] for p in points)+1):
            xs=[]
            for a,b in zip(points, points[1:]+points[:1]):
                if (a[1] <= y < b[1]) or (b[1] <= y < a[1]):
                    xs.append(round(a[0] + (y-a[1])*(b[0]-a[0])/(b[1]-a[1])))
            xs.sort()
            for x1,x2 in zip(xs[::2],xs[1::2]):
                for x in range(x1,x2+1): self.pixel(x,y,c)
    def text(self, x, y, value, scale, c):
        for ch in value.upper():
            glyph=FONT.get(ch,FONT[" "])
            for gy,row in enumerate(glyph):
                for gx,v in enumerate(row):
                    if v == "1": self.rect(x+gx*scale,y+gy*scale,scale,scale,c)
            x += 6*scale
    def gradient(self):
        for y in range(self.h):
            for x in range(self.w):
                glow=max(0.0,1.0-math.hypot(x-self.w*.82,y-self.h*.2)/(self.w*.85))
                self.pixel(x,y,(8+round(26*glow),11+round(20*glow),20+round(65*glow)))
    def save(self,path):
        def chunk(kind,data):
            return struct.pack(">I",len(data))+kind+data+struct.pack(">I",zlib.crc32(kind+data)&0xffffffff)
        rows=b"".join(b"\0"+self.p[y*self.w*3:(y+1)*self.w*3] for y in range(self.h))
        png=b"\x89PNG\r\n\x1a\n"+chunk(b"IHDR",struct.pack(">IIBBBBB",self.w,self.h,8,2,0,0,0))+chunk(b"IDAT",zlib.compress(rows,9))+chunk(b"IEND",b"")
        path.write_bytes(png)

def banner():
    c=Canvas(320,180); c.gradient()
    c.circle(57,75,38,(101,82,220)); c.polygon([(48,55),(48,95),(78,75)],(255,255,255))
    c.text(108,45,"NOVA",6,(248,249,255)); c.text(258,45,"TV",3,(164,151,255))
    c.text(109,116,"YOUR PLAYLIST",2,(181,188,211)); c.text(109,137,"YOUR TV",2,(181,188,211))
    c.rect(0,174,320,6,(126,105,244)); c.save(OUT/"tv_banner.png")

def icon():
    c=Canvas(192,192); c.gradient()
    c.circle(96,96,77,(105,83,225)); c.circle(96,96,62,(30,24,69))
    c.polygon([(78,56),(78,136),(139,96)],(255,255,255)); c.save(OUT/"ic_launcher.png")

if __name__ == "__main__":
    OUT.mkdir(parents=True,exist_ok=True)
    banner(); icon()
    print("Generated tv_banner.png (320x180) and ic_launcher.png (192x192)")
