package dev.pointofpressure.minecom.data;

import net.kyori.adventure.sound.Sound;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.attribute.AttributeOperation;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.component.AttributeList;
import net.minestom.server.sound.SoundEvent;

/**
 * Item stats read from the items' official data components — attack damage and
 * armor come from ATTRIBUTE_MODIFIERS, durability from MAX_DAMAGE/DAMAGE —
 * no hardcoded stat tables.
 */
public final class Items {
    private Items() {}

    /** Player base attack (1.0) plus the item's additive attack_damage modifiers. */
    public static float attackDamage(ItemStack stack) {
        return 1f + (float) attributeTotal(stack, Attribute.ATTACK_DAMAGE);
    }

    public static double armorPoints(ItemStack stack) {
        return attributeTotal(stack, Attribute.ARMOR);
    }

    public static double armorToughness(ItemStack stack) {
        return attributeTotal(stack, Attribute.ARMOR_TOUGHNESS);
    }

    private static double attributeTotal(ItemStack stack, Attribute attribute) {
        if (stack.isAir()) return 0;
        AttributeList list = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (list == null) return 0;
        double total = 0;
        for (AttributeList.Modifier modifier : list.modifiers()) {
            if (modifier.attribute() == attribute
                    && modifier.modifier().operation() == AttributeOperation.ADD_VALUE) {
                total += modifier.modifier().amount();
            }
        }
        return total;
    }

    /**
     * Apply wear to an item. Returns the damaged stack, or AIR when it breaks
     * (with the break sound played to the holder).
     */
    public static ItemStack damageItem(Player holder, ItemStack stack, int amount) {
        if (stack.isAir()) return stack;
        Integer max = stack.get(DataComponents.MAX_DAMAGE);
        if (max == null || max <= 0) return stack;
        int unbreaking = Enchants.level(stack, "unbreaking");
        if (unbreaking > 0 && Math.random() < unbreaking / (unbreaking + 1.0)) return stack;
        Integer current = stack.get(DataComponents.DAMAGE);
        int damage = (current == null ? 0 : current) + amount;
        if (damage >= max) {
            holder.playSound(Sound.sound(SoundEvent.ENTITY_ITEM_BREAK, Sound.Source.PLAYER, 1f, 1f));
            return ItemStack.AIR;
        }
        return stack.with(builder -> builder.set(DataComponents.DAMAGE, damage));
    }
}
