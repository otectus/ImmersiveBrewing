"""Original, authored 16-pixel materials and sprites (GPL-3.0 with the mod).

Each material uses deliberate clusters; effects and symbols have their own silhouettes.
The deterministic generator owns the exported PNGs. No Minecraft pixels are copied.
"""
from pathlib import Path
from PIL import Image

IRON = [(43,45,49),(58,61,66),(76,80,86),(97,102,109),(124,130,137)]
COPPER = [(114,62,43),(151,85,57),(183,112,76),(205,138,98),(226,170,128)]
PATINA = [(65,116,99),(86,143,119),(115,167,139)]
STONE = [(91,92,90),(114,116,112),(133,135,130),(151,153,147),(171,173,166)]
WOOD = [(73,50,32),(96,66,40),(120,85,49),(143,104,61),(164,125,78)]
BRICK = [(111,55,44),(139,73,57),(163,92,70),(183,114,88)]
MORTAR = [(139,135,121),(158,152,136)]
PAPER = [(184,170,135),(214,202,170),(235,226,203),(248,242,223)]
CLOTH = [(104,81,51),(146,116,76),(183,152,105),(214,188,141)]
LEATHER = [(65,40,29),(94,54,33),(125,75,44),(154,98,57)]
WAX = [(119,30,32),(170,48,44),(208,82,66)]
SALT = [(111,114,112),(151,156,151),(188,196,186),(226,233,218)]
FIRE = [(184,63,26),(231,102,30),(255,167,48),(255,220,111)]
OUTLINE = (35,30,25)


def hash2(x,y,seed):
    h=(x*374761393+y*668265263+seed*2147483647)&0xFFFFFFFF
    h=(h^(h>>13))*1274126177&0xFFFFFFFF
    return (h^(h>>16))&0xFFFF


def blank(w=16,h=16): return Image.new('RGBA',(w,h),(0,0,0,0))


def put(im,x,y,c):
    if 0<=x<im.width and 0<=y<im.height: im.putpixel((x,y),c if len(c)==4 else (*c,255))


def rect(im,x0,y0,x1,y1,c):
    for y in range(y0,y1):
        for x in range(x0,x1): put(im,x,y,c)


def stamp(rows,palette):
    assert len(rows)==16 and all(len(r)==16 for r in rows), [(i,len(r)) for i,r in enumerate(rows)]
    im=blank()
    for y,row in enumerate(rows):
        for x,c in enumerate(row):
            if c!='.': put(im,x,y,palette[c])
    return im


# Separate authored cluster maps: cast iron, granular stone, worked copper and polished steel.
# Midtones carry the surface. Small darker pockets and broken highlights give it material, not noise.
IRON_ROWS=[
 '2233322232332221','2334322322222112','2323223322112222','2222222221233322',
 '1122222112234322','1212332222223322','2223443223322221','2223322223432212',
 '3322221222322222','3432112222222332','2322223322112343','2222234322212232',
 '2112233223322222','1222222223432211','2223332112322221','2234321222222332']
STONE_ROWS=[
 '2333222122233322','3443321122344332','2332211222333321','2222122333222112',
 '1222223443322222','1123322333222332','2224332211123443','2333322112222332',
 '3443222233322221','2332212344322112','2221122333321222','2212222222212332',
 '1222333221123443','2223443322222332','2212332212332222','2322221123443222']
COPPER_ROWS=[
 '3332233332222322','3443234433222223','2333233332221223','2233332322111232',
 '2234433222122222','2334433322223322','2333322222334432','3322221122344333',
 '3222111222333323','2221122233322222','2322222344332212','3332222343322112',
 '3443222233322223','3333221222222333','2322211222333443','2222222233344332']
STEEL_ROWS=[
 '2222333333332222','2333444443333222','3333333322222333','2222222222222222',
 '1112222221111122','2222222233332222','2333333444333322','3333333333322222',
 '2222222222222222','2211111222221112','2222222333333222','2333333444433332',
 '3333333333332222','2222222222222222','1112222111122222','2222222222233332']


def kettle_iron():
    return stamp(IRON_ROWS,{'1':(52,55,60),'2':(68,72,78),'3':(81,86,93),'4':(94,100,107)})


def copper_sheet(seed=0):
    return stamp(COPPER_ROWS,{'1':(149,77,53),'2':(177,100,68),'3':(195,118,82),'4':(213,139,99)})


def kettle_copper():
    im=copper_sheet()
    for x,y,c in [(1,13,0),(2,13,1),(1,14,0),(2,14,0),(3,14,1),(3,15,0),(14,2,1),(14,3,0)]: put(im,x,y,PATINA[c])
    return im


def kettle_rim():
    # The handled lip is burnished brighter than the worked, weathered vessel walls.
    return stamp(COPPER_ROWS,{'1':(172,96,61),'2':(195,120,77),'3':(215,145,96),'4':(234,174,122)})


def alembic_copper(): return copper_sheet()


def kettle_burner():
    im=blank()
    for y in range(16):
        offset=4 if y//4%2 else 0
        for x in range(16):
            bx=(x+offset)%8
            if y%4==3 or bx==7: c=(113,105,92) if y%4==3 else (135,124,108)
            else:
                shade=3 if y%4==0 and bx<5 else 0 if bx==6 else 1+(hash2(x//2,y//2,4)%2)
                c=BRICK[shade]
            put(im,x,y,c)
    return im


def alembic_brick(): return kettle_burner()


def vent(lit):
    # The firebox is four model pixels high and samples v=12..16. Paint the actual visible UV band.
    im=kettle_burner();rect(im,3,12,13,16,IRON[1]);rect(im,4,13,12,15,(24,26,28))
    if lit:
        for x in (5,6,8,9,11):put(im,x,14,FIRE[2 if x%2 else 1])
    for x in (4,7,10):
        put(im,x,13,IRON[3]);put(im,x,14,IRON[2])
    rect(im,3,12,13,13,IRON[3])
    return im


def kettle_vent(): return vent(True)
def kettle_vent_cold(): return vent(False)


def planks(seed=0):
    im=blank()
    for y in range(16):
        row=y//4
        for x in range(16):
            # Four offset boards, broken grain, and a shaded joint; not repeated ladder rungs.
            joint=(row*7+5)%16
            shade=2+(hash2(x//3,row,7)%2)
            if y%4==3: shade=0 if x!=joint else 1
            elif x==joint: shade=1
            elif y%4==0: shade=min(4,shade+1)
            elif (x+row*3)%11 in (2,3,4): shade=max(1,shade-1)
            put(im,x,y,WOOD[shade])
    return im


def kettle_rack(): return planks()
def mortar_wood(): return planks()
def solid_wood():
    # Long grain for handles and small fittings. Board joints belong on shelves, never around a tool shaft.
    return stamp([
        '2223322221122232','2223322221222232','2233222221222232','2233222212222232',
        '2233222112222332','2223221122222332','2223221222222332','2223321222222332',
        '2223321222222322','2223322122222322','2233322122222322','2233222122222322',
        '2233222212222332','2233222211222332','2223322211222332','2223322221222232'],
        {'1':(98,67,40),'2':(123,87,51),'3':(145,106,63)})
def mortar_stone():
    return stamp(STONE_ROWS,{'1':(103,105,102),'2':(123,126,121),'3':(140,143,137),'4':(157,160,153)})


def mortar_pestle():
    return stamp(STONE_ROWS,{'1':(187,180,160),'2':(208,201,181),'3':(221,215,195),'4':(234,229,211)})


def mortar_inner(): return stamp(STONE_ROWS,{'1':(70,73,70),'2':(88,92,87),'3':(101,106,99),'4':(115,120,111)})
def steel(): return stamp(STEEL_ROWS,{'1':(143,156,163),'2':(175,188,194),'3':(202,213,215),'4':(227,234,230)})
def ceramic():
    im=Image.new('RGBA',(16,16),(230,220,195,255))
    for x,y,w in [(2,3,3),(3,4,2),(10,10,3),(11,11,2)]: rect(im,x,y,x+w,y+1,(223,212,185))
    for x,y,w in [(8,2,3),(7,3,2),(2,12,3)]: rect(im,x,y,x+w,y+1,(238,230,210))
    return im


def paper():
    im=Image.new('RGBA',(16,16),(*PAPER[2],255))
    for x,y,w in [(2,4,4),(10,8,3),(5,13,4)]: rect(im,x,y,x+w,y+1,(231,221,195))
    return im


def neutral(): return Image.new('RGBA',(16,16),(255,255,255,255))


def alembic_glass():
    im=blank()
    # No opaque repeated window frame: actual copper collars provide its structure.
    rect(im,0,0,16,16,(195,222,225,15))
    for x,y in [(1,3),(2,2),(3,1),(2,4),(3,3),(4,2),(11,13),(12,12),(13,11)]: put(im,x,y,(222,244,244,105))
    return im


def paddle(blade,handle,seed=0):
    # Blade and shaft share one axis. The former vertical blade made the tool look bent.
    return stamp([
        '................','............oo..','...........oHho.','..........oHMMho',
        '.........oHMMMho','........oHMMMho.','........oHMMho..','........oMMho...',
        '.......sabs.....','......sabs......','.....sabs.......','....sabs........',
        '...sabs.........','..sabs..........','..sbs...........','................'],
        {'o':blade[0],'h':blade[1],'M':blade[2],'H':blade[-1],
         's':handle[0],'a':handle[3],'b':handle[1]})


def wooden_brewing_paddle(): return paddle(WOOD,WOOD)
def iron_brewing_paddle(): return paddle([(73,80,87),(112,124,132),(164,176,180),(209,219,216),(239,243,233)],WOOD)


def prepared_reagent_kit():
    return stamp([
      '................','.......tt.......','......tHHt......','.....tHhHht.....',
      '....ooorrooo....','.....121221.....','....12333221....','...1233333221...',
      '..123323332221..','..123223322221..','..1222232PPP21..','..1222222PwP21..',
      '...122222PwwP1..','....11111PPPP...','.....ooooooo....','................'],
      {'o':OUTLINE,'t':LEATHER[0],'H':CLOTH[3],'h':CLOTH[1],'r':WAX[1],
       '1':CLOTH[0],'2':CLOTH[1],'3':CLOTH[2],'P':PAPER[2],'w':WOOD[1]})


def carrier():
    return stamp([
     '................','.....oooooo.....','....o122221o....','...o12....21o...',
     '..o1111111111o..','..o2333333331o..','..o2nh.nh.nh1o..','..o2bg.bg.bg1o..',
     '..o2bG.bG.bG1o..','..o2BG.BG.BG1o..','..o2333333331o..','..o2122222211o..',
     '..o2223333221o..','..o2111111111o..','...oooooooooo...','................'],
     {'o':OUTLINE,'1':WOOD[0],'2':WOOD[2],'3':WOOD[3],'n':COPPER[2],'h':(212,230,232),
      'b':(97,157,194),'g':(176,216,230),'G':(70,116,166),'B':(67,97,142)})


def batch_carrier(): return carrier()
def batch_carrier_strapped():
    im=carrier()
    for y in range(3,14):
        x=3+(y-3)//3
        put(im,x,y,LEATHER[0]);put(im,x+1,y,LEATHER[2])
    rect(im,5,8,8,11,IRON[1]);rect(im,6,9,7,10,IRON[4])
    return im


def recovery_batch():
    im=stamp([
     '................','.......hh.......','....hhhnnhhh....','...o11111111o...',
     '..o2333333331o..','..o2222222221o..','..o2111111111o..','..o2233333321o..',
     '..o2222222221o..','..o2222222221o..','..o2333333331o..','..o2222222221o..',
     '..o2111111111o..','...oooooooooo...','................','................'],
     {'o':OUTLINE,'1':WOOD[0],'2':WOOD[2],'3':WOOD[3],'h':(179,218,225),'n':COPPER[3]})
    rect(im,7,4,9,10,WAX[0]);rect(im,6,6,10,9,WAX[1]);put(im,7,6,WAX[2]);put(im,6,8,WAX[0]);put(im,9,8,WAX[0])
    return im


def brewers_notebook():
    return stamp([
     '................','......oooooo....','....oo333332o...','..oo223333321o..',
     '..o1122333321Po.','..o1122nn3321PPo','..o1122hh3321PPo','..o112hGGh321PPo',
     '..o112hGGh321PPo','..o112hGgh321PPo','..o1122hh2321PPo','..o1122333321PPo',
     '..o1122222221PPo','...oo11111111Po.','.....oooooooo...','................'],
     {'o':OUTLINE,'1':LEATHER[0],'2':LEATHER[1],'3':LEATHER[2],'P':PAPER[2],
      'n':COPPER[3],'h':(189,218,202),'G':(47,133,85),'g':(81,168,103)})


def spent_alchemical_salt():
    return stamp([
     '................','................','................','................',
     '................','........h.......','.......hHh......','.....h.hHh......',
     '....hHhHHHh.....','...hHHHHLHHh....','..hHHHHLHHHLh...', '.hHHHLLHHHLLLh..',
     '.oLLLLLLLLLLLLo.','..oooooooooooo..','................','................'],
     {'o':SALT[0],'h':SALT[1],'L':SALT[2],'H':SALT[3]})


# Readable shapes, not color-only dot patterns. Flames meet their burner at the bottom edge.
EFFECT_ROWS={
 'bubble':[
 '................','................','................','.....HHHHHH.....',
 '....H......h....','...H........h...','...H........h...','...H........h...',
 '...H........h...','...H........h...','....h......h....','.....hhhhhh.....',
 '................','................','................','................'],
 'ripple':[
 '................','................','................','................',
 '.....hhhhhh.....','...hh......hh...','..h..........h..','..H..........h..',
 '...HH......hh...','.....HHHHHH.....','................','................',
 '................','................','................','................'],
 'foam':[
 '................','................','................','................',
 '.....hh.........','....hHHh..hh....','...hHHHHhhHHh...','..hHHHHHHHHHHh..',
 '..hHHHHHHHHHh...','...hhHHHHHhh....','.....hhhhh......','................',
 '................','................','................','................'],
 'flame':[
 '.........o......','.........a......','........oa......','........ay......',
 '........ay......','.......oay......','.......ayy......','......oayya.....',
 '......ayyya.....','......aycya.....','.....oayccya....','.....aycccya....',
 '.....aycccya....','.....aycccya....','......ycccy.....','......yyyyy.....'],
 'shard':[
 '................','................','........h.......','.......hH.......',
 '......hHHh......','......hHHH......','.....hHHHHh.....','.....hHHHHh.....',
 '.....hHHHHH.....','......hHHHh.....','......hHHh......','.......hh.......',
 '................','................','................','................'],
 'flake':[
 '................','................','................','................',
 '......hhh.......','....hhHHHhh.....','...hHHHHHHHh....','...hHHHHHHh.....',
 '....hhHHhh......','......hh........','................','................',
 '................','................','................','................'],
 'spark':[
 '................','................','................','.......H........',
 '.......H........','......hHh.......','...HHHHHHHHH....','......hHh.......',
 '.......H........','.......h........','................','................',
 '................','................','................','................']}


def effect(kind):
    if kind=='flame':
        # A tapered tongue with a pale core: no dark sticker-like outline or padded, floating base.
        return stamp(EFFECT_ROWS[kind],{'o':(238,105,29,155),'a':(255,151,40,230),
                                      'y':(255,202,78,250),'c':(255,237,170,255)})
    return stamp(EFFECT_ROWS[kind],{'h':(180,190,190,210),'H':(255,255,255,245)})


def gui_symbol(kind):
    # Match vanilla's subdued empty-slot marks; no colored fake item stacks.
    if kind=='bottle':
        return stamp([
         '................','......hhhh......','......hHHh......','.......hh.......',
         '.......hh.......','......hHHh......','.....hH..Hh.....','....hH....Hh....',
         '....H......H....','....H......H....','....hH....Hh....','.....hHHHHh.....',
         '......hhhh......','................','................','................'],
         {'h':(97,99,99,155),'H':(175,177,174,170)})
    if kind=='paddle': source=wooden_brewing_paddle()
    elif kind=='reagent': source=preparation_material(0,0)
    elif kind=='fuel': source=effect('flame')
    else:
        return stamp([
          '................','................','................','.........hh.....',
          '.........hHh....','....hhhhhhHHh...','....hHHHHHHHHh..','....hHhhhhHHh...',
          '....hH...hHh....','....hH...hh.....','....hH..........','....hh..........',
          '................','................','................','................'],
          {'h':(97,99,99,155),'H':(175,177,174,170)})
    out=blank()
    for y in range(16):
        for x in range(16):
            r,g,b,a=source.getpixel((x,y))
            if a:
                value=101+round((r*.2126+g*.7152+b*.0722)*.23)
                put(out,x,y,(value,value+2,value,165))
    return out


def preparation_material(mode,stage):
    im=blank();coarse=3-stage
    palette=[SALT,WOOD,CLOTH,PAPER,[(100,143,153),(127,170,177),(159,196,199),(194,220,217)]][mode]
    if mode==4:
        # A contained puddle with an irregular meniscus and a mixing streak that shrinks with refinement.
        for y,x0,x1 in [(4,6,10),(5,4,12),(6,3,13),(7,2,14),(8,2,14),(9,3,13),(10,4,12),(11,6,10)]:
            for x in range(x0,x1):
                edge=x in (x0,x1-1) or y in (4,11)
                put(im,x,y,palette[0 if edge else 2])
        for x,y in [(5,5),(6,5),(4,6),(3,7),(6,10)]:put(im,x,y,palette[3])
        for i in range(3-stage):
            rect(im,7+i,6+i,10+i,7+i,palette[1])
        return im
    if mode==2:
        for y in range(4,13):
            for x in range(2,14):
                if (x in (2,13) and y in (4,5,12)) or (y==4 and x in (3,12)): continue
                c=palette[1 if (x in (2,13) or y==12) else 2]
                put(im,x,y,c)
        for x,y in [(4,7),(8,5),(10,9),(6,11)]:
            rect(im,x,y,x+max(1,3-stage),y+1,palette[3])
            if stage<2:put(im,x,y+1,palette[0])
        return im
    if stage==3:
        for y in range(6,12):
            inset=2 if y==6 else 1 if y in (7,11) else 0
            for x in range(3+inset,13-inset): put(im,x,y,palette[1 if y==11 else 2])
        for x,y in [(6,7),(7,7),(4,9),(5,9),(9,8),(10,8),(9,10)]: put(im,x,y,palette[3])
        if mode==1:
            for x,y in [(5,8),(8,9),(10,10)]: put(im,x,y,palette[0]);put(im,x+1,y,palette[1])
        return im
    centers=[(4,5),(10,4),(7,8),(12,10),(4,11)]
    for i,(cx,cy) in enumerate(centers):
        if mode==1:
            for j in range(4-stage): put(im,cx+j,cy+j//3,palette[2]);put(im,cx+j,cy+j//3+1,palette[0])
        elif mode==3:
            for j in range(3-stage): put(im,cx+j,cy,palette[3]);put(im,cx+j,cy+1,palette[1])
        else:
            size=max(1,coarse)
            rect(im,cx,cy,cx+size,cy+size,palette[1])
            put(im,cx,cy,palette[3])
            if size>1:
                put(im,cx+1,cy,palette[2]);put(im,cx,cy+1,palette[2])
    return im


def signature(index):
    im=blank()
    colors=[(194,221,231),(231,237,220),(168,196,176),(234,224,196),(169,133,96),(249,222,131),
            (236,140,69),(161,217,225),(144,112,164),(229,222,164),(218,203,166)]
    c=colors[index]; dark=tuple(int(v*.65) for v in c)
    def line(points,color=c):
        for x,y in points: put(im,x,y,color)
    def ring(cx,cy,r):
        for dx,dy in [(0,-r),(1,-r),(-1,-r),(r,0),(r,1),(r,-1),(0,r),(1,r),(-1,r),(-r,0),(-r,1),(-r,-1)]: put(im,cx+dx,cy+dy,c)
    if index==0: im=effect('ripple')
    elif index==1:
        ring(5,10,2);ring(11,6,2);put(im,4,4,c)
    elif index==2:
        for x,y,w in [(3,5,7),(5,7,8),(2,9,8),(6,11,6)]: rect(im,x,y,x+w,y+1,(*c,150))
    elif index==3: im=effect('foam')
    elif index==4:
        for x,y in [(3,11),(5,9),(8,11),(10,9),(12,12),(6,12)]: rect(im,x,y,x+2,y+1,c);put(im,x,y+1,dark)
    elif index==5:
        line([(3,10),(4,9),(5,8),(7,8),(8,7),(9,6),(11,6),(12,5)]);line([(5,12),(6,11),(9,10),(10,9)],dark)
    elif index==6:
        for offset in (0,4,8): line([(3+offset,5),(4+offset,6),(4+offset,7),(5+offset,8),(5+offset,9),(6+offset,10)])
    elif index==7:
        ring(7,8,4);ring(12,4,1);put(im,7,7,c)
    elif index==8:
        line([(5,3),(6,3),(7,3),(8,3),(9,3),(10,4),(11,5),(11,6),(11,7),(11,8),(10,9),(9,10),(8,10),(7,10),(6,9),(6,8),(6,7),(7,6),(8,6),(9,7),(9,8)])
        line([(4,4),(3,5),(3,6),(3,7),(3,8),(4,10),(5,11),(6,12),(7,12),(8,12)],dark)
    elif index==9:
        for cx,cy in [(4,11),(8,8),(12,5)]: line([(cx,cy-1),(cx-1,cy),(cx,cy),(cx+1,cy),(cx,cy+1)])
    else:
        for x,y in [(4,4),(10,6),(6,10),(11,12)]: rect(im,x,y,x+2,y+1,c);put(im,x,y+1,dark)
    if index in (0,3):
        for y in range(16):
            for x in range(16):
                r,g,b,a=im.getpixel((x,y))
                if a: put(im,x,y,(*(v*r//255 for v in c),a))
    return im


def logo():
    with Image.open(Path(__file__).resolve().parent.parent/'project-icon-32.png') as master:
        return master.convert('RGBA').resize((128,128),Image.Resampling.NEAREST)


BLOCKS={'kettle_iron':kettle_iron,'kettle_copper':kettle_copper,'kettle_rim':kettle_rim,'kettle_burner':kettle_burner,
        'kettle_vent':kettle_vent,'kettle_vent_cold':kettle_vent_cold,'kettle_rack':kettle_rack,
        'mortar_stone':mortar_stone,'mortar_wood':mortar_wood,'mortar_pestle':mortar_pestle,
        'alembic_copper':alembic_copper,'alembic_brick':alembic_brick,'alembic_glass':alembic_glass,
        'mortar_inner':mortar_inner,'apparatus_wood':solid_wood,'apparatus_steel':steel,'apparatus_ceramic':ceramic,'apparatus_paper':paper,'apparatus_neutral':neutral}
ITEMS={'wooden_brewing_paddle':wooden_brewing_paddle,'iron_brewing_paddle':iron_brewing_paddle,
       'prepared_reagent_kit':prepared_reagent_kit,'recovery_batch':recovery_batch,'batch_carrier':batch_carrier,
       'batch_carrier_strapped':batch_carrier_strapped,'brewers_notebook':brewers_notebook,'spent_alchemical_salt':spent_alchemical_salt}


def textures():
    out={}
    for name,fn in BLOCKS.items(): out[f'assets/immersive_brewing/textures/block/{name}.png']=fn()
    for kind in EFFECT_ROWS: out[f'assets/immersive_brewing/textures/block/fx_{kind}.png']=effect(kind)
    for name,fn in ITEMS.items(): out[f'assets/immersive_brewing/textures/item/{name}.png']=fn()
    for mode in range(5):
        for stage in range(4): out[f'assets/immersive_brewing/textures/gui/preparation/{mode}_{stage}.png']=preparation_material(mode,stage)
    for kind in ('bottle','reagent','fuel','paddle','returns'): out[f'assets/immersive_brewing/textures/gui/slot_roles/{kind}.png']=gui_symbol(kind)
    for index in range(11): out[f'assets/immersive_brewing/textures/gui/signatures/{index}.png']=signature(index)
    out['logo.png']=logo()
    return out
