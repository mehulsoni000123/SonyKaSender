from PIL import Image, ImageDraw, ImageFilter
import os, sys
root = sys.argv[1]
S = 1024
def icon(size, rounded=True):
    img = Image.new('RGBA', (S, S), (0,0,0,0))
    bg = Image.new('RGBA', (S, S))
    px = bg.load()
    for y in range(S):
        for x in range(S):
            t = (x*0.35 + y*0.65)/S
            r = int(52 + (98-52)*t); g = int(40 + (60-40)*t); b = int(140 + (230-140)*t)
            px[x,y] = (r, g, b, 255)
    # soft light orb
    orb = Image.new('RGBA', (S,S), (0,0,0,0)); od = ImageDraw.Draw(orb)
    od.ellipse((180,520,860,1100), fill=(140,200,255,120)); orb = orb.filter(ImageFilter.GaussianBlur(140))
    bg = Image.alpha_composite(bg, orb)
    mask = Image.new('L', (S,S), 0); ImageDraw.Draw(mask).rounded_rectangle((0,0,S-1,S-1), radius=230 if rounded else 0, fill=255)
    img.paste(bg, (0,0), mask)
    # back card (tilted)
    def card(angle, off, alpha, fill):
        c = Image.new('RGBA', (S,S), (0,0,0,0)); d = ImageDraw.Draw(c)
        d.rounded_rectangle((330,250,694,720), radius=34, fill=fill+(alpha,))
        return c.rotate(angle, resample=Image.BICUBIC, center=(512,520), translate=off)
    shadow = card(-14, (10,40), 120, (10,5,40)).filter(ImageFilter.GaussianBlur(30))
    img = Image.alpha_composite(img, shadow)
    img = Image.alpha_composite(img, card(-14, (-30,40), 110, (255,255,255)))
    front = Image.new('RGBA', (S,S), (0,0,0,0)); d = ImageDraw.Draw(front)
    d.rounded_rectangle((350,200,714,670), radius=34, fill=(255,255,255,255))
    d.rounded_rectangle((378,228,686,520), radius=18, fill=(120,160,255,255))
    d.ellipse((600,256,660,316), fill=(255,236,150,255))
    d.polygon([(378,520),(470,400),(540,470),(600,410),(686,520)], fill=(70,90,210,255))
    d.rounded_rectangle((388,560,620,584), radius=12, fill=(225,228,240,255))
    d.rounded_rectangle((388,605,560,629), radius=12, fill=(225,228,240,255))
    front = front.rotate(9, resample=Image.BICUBIC, center=(532,435))
    img = Image.alpha_composite(img, front)
    # sparkle
    sp = Image.new('RGBA', (S,S), (0,0,0,0)); d = ImageDraw.Draw(sp)
    cx, cy, R, r = 790, 210, 95, 20
    d.polygon([(cx,cy-R),(cx+r,cy-r),(cx+R,cy),(cx+r,cy+r),(cx,cy+R),(cx-r,cy+r),(cx-R,cy),(cx-r,cy-r)], fill=(255,255,255,255))
    img = Image.alpha_composite(img, sp)
    return img.resize((size,size), Image.LANCZOS)

big = icon(1024)
os.makedirs(f'{root}/pc/desk', exist_ok=True)
big.resize((256,256), Image.LANCZOS).save(f'{root}/pc/desk/icon.png')
big.save(f'{root}/pc/icon.ico', sizes=[(16,16),(32,32),(48,48),(64,64),(128,128),(256,256)])
for name, s in {'mdpi':48,'hdpi':72,'xhdpi':96,'xxhdpi':144,'xxxhdpi':192}.items():
    p = f'{root}/android/res/mipmap-{name}'; os.makedirs(p, exist_ok=True)
    big.resize((s,s), Image.LANCZOS).save(f'{p}/ic_launcher.png')
os.makedirs(f'{root}/android/assets', exist_ok=True)
big.resize((256,256), Image.LANCZOS).save(f'{root}/android/assets/icon.png')
print('ok')
